/*
 * Copyright (C) 2016 Kodehawa
 *
 * Mantaro is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * Mantaro is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 *
 */

package net.kodehawa.mantarobot.commands.music;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.bandcamp.BandcampAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.beam.BeamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.soundcloud.SoundCloudAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.twitch.TwitchStreamAudioSourceManager;
import com.sedmelluq.discord.lavaplayer.source.vimeo.VimeoAudioSourceManager;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.StageChannel;
import net.kodehawa.mantarobot.commands.music.requester.AudioLoader;
import net.kodehawa.mantarobot.commands.music.utils.AudioCmdUtils;
import net.kodehawa.mantarobot.core.command.slash.SlashContext;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.utils.Lazy;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MantaroAudioManager {
    private static final Lazy<Executor> LOAD_EXECUTOR = new Lazy<>(() -> Executors.newCachedThreadPool(
            new ThreadFactoryBuilder()
                    .setNameFormat("AudioLoadThread-%d")
                    .setDaemon(true)
                    .build()
    ));

    private final Map<String, GuildMusicManager> musicManagers;
    private final AudioPlayerManager playerManager;

    public MantaroAudioManager() {
        this.musicManagers = new ConcurrentHashMap<>();
        this.playerManager = new DefaultAudioPlayerManager();

        //Register source managers and configure the Player
        playerManager.registerSourceManager(SoundCloudAudioSourceManager.createDefault());
        playerManager.registerSourceManager(new BandcampAudioSourceManager());
        playerManager.registerSourceManager(new VimeoAudioSourceManager());
        playerManager.registerSourceManager(new TwitchStreamAudioSourceManager());
        playerManager.registerSourceManager(new BeamAudioSourceManager());
    }

    public GuildMusicManager getMusicManager(Guild guild) {
        return musicManagers.computeIfAbsent(guild.getId(), id -> new GuildMusicManager(guild.getId()));
    }

    public void resetMusicManagerFor(String id) {
        var previousManager = musicManagers.get(id);
        if (previousManager == null) // Nothing to do?
            return;

        previousManager.destroy();
        musicManagers.remove(id);
    }

    public long getTotalQueueSize() {
        return musicManagers.values().stream().map(m -> m.getTrackScheduler().getQueue().size()).mapToInt(Integer::intValue).sum();
    }

    public void loadAndPlay(SlashContext ctx, String trackUrl, boolean skipSelection, boolean addFirst, I18nContext lang) {
        AudioCmdUtils.connectToVoiceChannel(ctx, lang).thenAcceptAsync(bool -> {
            if (bool) {
                var musicManager = getMusicManager(ctx.getGuild());
                var scheduler = musicManager.getTrackScheduler();

                scheduler.getMusicPlayer().setPaused(false);

                if (scheduler.getQueue().isEmpty()) {
                    scheduler.setRepeatMode(null);
                }

                var state = scheduler.getGuild().getSelfMember().getVoiceState();
                if (state != null && state.getChannel() != null && state.getChannel() instanceof StageChannel stageChannel) {
                    try {
                        stageChannel.requestToSpeak().queue();
                    } catch (IllegalStateException ignored) { }
                }

                var loader = new AudioLoader(musicManager, ctx, skipSelection, addFirst);
                playerManager.loadItemOrdered(musicManager, trackUrl, loader);
            }
        }, LOAD_EXECUTOR.get());
    }

    public Map<String, GuildMusicManager> getMusicManagers() {
        return this.musicManagers;
    }

    public AudioPlayerManager getPlayerManager() {
        return this.playerManager;
    }
}

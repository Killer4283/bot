/*
 * Copyright (C) 2016-2017 David Alejandro Rubio Escares / Kodehawa
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
 * along with Mantaro.  If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.commands.music.requester;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.music.GuildMusicManager;
import net.kodehawa.mantarobot.commands.music.utils.AudioUtils;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.DBGuild;
import net.kodehawa.mantarobot.db.entities.DBUser;
import net.kodehawa.mantarobot.db.entities.helpers.GuildData;
import net.kodehawa.mantarobot.utils.DiscordUtils;
import net.kodehawa.mantarobot.utils.SentryHelper;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;

import java.awt.*;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AudioLoader implements AudioLoadResultHandler {
    public static final int MAX_QUEUE_LENGTH = 350;
    public static final long MAX_SONG_LENGTH = 1800000; //30 minutes
    private final GuildMessageReceivedEvent event;
    private final GuildMusicManager musicManager;
    private final boolean skipSelection;
    private final String trackUrl;

    public AudioLoader(GuildMusicManager musicManager, GuildMessageReceivedEvent event, String trackUrl, boolean skipSelection) {
        this.musicManager = musicManager;
        this.trackUrl = trackUrl;
        this.event = event;
        this.skipSelection = skipSelection;
    }

    @Override
    public void trackLoaded(AudioTrack track) {
        loadSingle(track, false);
    }

    @Override
    public void playlistLoaded(AudioPlaylist playlist) {
        if(playlist.isSearchResult()) {
            if(!skipSelection) onSearch(playlist);
            else loadSingle(playlist.getTracks().get(0), false);
            return;
        }

        try {
            int i = 0;
            for(AudioTrack track : playlist.getTracks()) {
                GuildData guildData = MantaroData.db().getGuild(event.getGuild()).getData();
                if(guildData.getMusicQueueSizeLimit() != null) {
                    if(i < guildData.getMusicQueueSizeLimit()) {
                        loadSingle(track, true);
                    } else {
                        event.getChannel().sendMessage(String.format(":warning: The queue you added had more than %d songs, so we added songs until this limit and ignored the rest.", guildData.getMusicQueueSizeLimit())).queue();
                        break;
                    }
                } else {
                    if(i < MAX_QUEUE_LENGTH) {
                        loadSingle(track, true);
                    } else {
                        event.getChannel().sendMessage(":warning: The queue you added had more than " + MAX_QUEUE_LENGTH + " songs, so we added songs until this limit and ignored the rest.").queue();
                        break;
                    }
                }
                i++;
            }

            event.getChannel().sendMessage(String.format(
                    "%sAdded **%d songs** to queue on playlist: **%s** *(%s)*",
                    EmoteReference.CORRECT,
                    i,
                    playlist.getName(),
                    Utils.getDurationMinutes(playlist.getTracks().stream().mapToLong(temp -> temp.getInfo().length).sum())
            )).queue();
        } catch(Exception e) {
            SentryHelper.captureExceptionContext(
                    "Cannot load playlist. I guess something broke pretty hard. Please check", e, this.getClass(), "Music Loader"
            );
        }
    }

    @Override
    public void noMatches() {
        event.getChannel().sendMessage(EmoteReference.ERROR + "The search yielded no results. If this appears for *all songs* you try to search for, you might want to use" +
                "`~>play soundcloud <search term>` instead on the meanwhile. Direct links to youtube should work too.").queue();
    }


    @Override
    public void loadFailed(FriendlyException exception) {
        if(!exception.severity.equals(FriendlyException.Severity.FAULT)) {
            event.getChannel().sendMessage("\u274C Error while fetching music: " + exception.getMessage()).queue();
        } else {
            log.warn("Error caught while playing audio, the bot might be able to continue playing music.", exception);
        }
    }

    public GuildMusicManager getMusicManager() {
        return musicManager;
    }

    private void loadSingle(AudioTrack audioTrack, boolean silent) {
        AudioTrackInfo trackInfo = audioTrack.getInfo();
        audioTrack.setUserData(event.getAuthor().getId());
        DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
        DBUser dbUser = MantaroData.db().getUser(event.getMember());
        GuildData guildData = dbGuild.getData();

        String title = trackInfo.title;
        long length = trackInfo.length;

        long queueLimit = !Optional.ofNullable(dbGuild.getData().getMusicQueueSizeLimit()).isPresent() ? MAX_QUEUE_LENGTH :
                dbGuild.getData().getMusicQueueSizeLimit();
        int fqSize = guildData.getMaxFairQueue();

        if(getMusicManager().getTrackScheduler().getQueue().size() > queueLimit && !dbUser.isPremium() && !dbGuild.isPremium()) {
            if(!silent)
                event.getChannel().sendMessage(String.format(":warning: Could not queue %s: Surpassed queue song limit!", title)).queue(
                        message -> message.delete().queueAfter(30, TimeUnit.SECONDS)
                );
            return;
        }

        if(audioTrack.getInfo().length > MAX_SONG_LENGTH && !dbUser.isPremium() && !dbGuild.isPremium()) {
            event.getChannel().sendMessage(String.format(":warning: Could not queue %s: Track is longer than 30 minutes! (%s)", title, AudioUtils.getLength(length))).queue();
            return;
        }

        //Comparing if the URLs are the same to be 100% sure they're just not spamming the same url over and over again.
        if(musicManager.getTrackScheduler().getQueue().stream().filter(track -> track.getInfo().uri.equals(audioTrack.getInfo().uri)).count() > fqSize && !silent) {
            event.getChannel().sendMessage(EmoteReference.ERROR + String.format("**Surpassed fair queue level of %d (Too many songs which are exactly equal)**", fqSize + 1)).queue();
            return;
        }

        musicManager.getTrackScheduler().queue(audioTrack);
        musicManager.getTrackScheduler().setRequestedChannel(event.getChannel().getIdLong());

        if(!silent) {
            event.getChannel().sendMessage(
                    String.format("\uD83D\uDCE3 Added to queue -> **%s** **(%s)**", title, AudioUtils.getLength(length))
            ).queue();
        }

        MantaroBot.getInstance().getStatsClient().increment("tracks_loaded");
    }

    private void onSearch(AudioPlaylist playlist) {
        List<AudioTrack> list = playlist.getTracks();
        DiscordUtils.selectList(event, list.subList(0, Math.min(5, list.size())),
                track -> String.format("**[%s](%s)** (%s)", track.getInfo().title, track.getInfo().uri, Utils.getDurationMinutes(track.getInfo().length)),
                s -> new EmbedBuilder().setColor(Color.CYAN).setTitle("Song selection. Type the song number to continue.", null)
                        .setThumbnail("http://www.clipartbest.com/cliparts/jix/6zx/jix6zx4dT.png")
                        .setDescription(s)
                        .setFooter("This timeouts in 10 seconds.", null).build(),
                selected -> loadSingle(selected, false)
        );

        MantaroBot.getInstance().getStatsClient().increment("tracks_searched");
    }
}

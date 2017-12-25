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

package net.kodehawa.mantarobot.core.listeners.command;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.rethinkdb.gen.exc.ReqlError;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.events.Event;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.core.exceptions.PermissionException;
import net.dv8tion.jda.core.hooks.EventListener;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.custom.EmbedJSON;
import net.kodehawa.mantarobot.commands.custom.legacy.DynamicModifiers;
import net.kodehawa.mantarobot.core.listeners.entities.CachedMessage;
import net.kodehawa.mantarobot.core.listeners.events.ShardMonitorEvent;
import net.kodehawa.mantarobot.core.processor.core.ICommandProcessor;
import net.kodehawa.mantarobot.core.shard.MantaroShard;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.DBGuild;
import net.kodehawa.mantarobot.db.entities.Player;
import net.kodehawa.mantarobot.db.entities.helpers.GuildData;
import net.kodehawa.mantarobot.db.entities.helpers.LocalExperienceData;
import net.kodehawa.mantarobot.db.entities.helpers.PlayerData;
import net.kodehawa.mantarobot.utils.SentryHelper;
import net.kodehawa.mantarobot.utils.Snow64;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.commands.RateLimiter;
import net.kodehawa.mantarobot.utils.data.GsonDataManager;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

@Slf4j
public class CommandListener implements EventListener {
    private static final Map<String, ICommandProcessor> CUSTOM_PROCESSORS = new ConcurrentHashMap<>();
    private static final RateLimiter experienceRatelimiter = new RateLimiter(TimeUnit.SECONDS, 30);
    //Message cache of 35000 cached messages. If it reaches 35000 it will delete the first one stored, and continue being 35000
    @Getter
    private static final Cache<String, Optional<CachedMessage>> messageCache = CacheBuilder.newBuilder().concurrencyLevel(10).maximumSize(35000).build();
    //Commands ran this session.
    private static int commandTotal = 0;
    private final String[] boomQuotes = {
            "Seemingly Megumin exploded our castle...", "Uh-oh, seemingly my master forgot some zeros and ones on the floor :<",
            "W-Wait, what just happened?", "I-I think we got some fire going on here... you might want to tell my master to take a look.",
            "I've mastered explosion magic, you see?", "Maybe something just went wrong on here, but, u-uh, I can fix it!",
            "U-Uhh.. What did you want?"
    };
    private final ICommandProcessor commandProcessor;
    private final Random rand = new Random();
    private final Random random = new Random();
    private final MantaroShard shard;
    private final int shardId;

    public CommandListener(int shardId, MantaroShard shard, ICommandProcessor processor) {
        this.shardId = shardId;
        this.shard = shard;
        commandProcessor = processor;
    }

    public static void clearCustomProcessor(String channelId) {
        CUSTOM_PROCESSORS.remove(channelId);
    }

    public static String getCommandTotal() {
        return String.valueOf(commandTotal);
    }

    public static void setCustomProcessor(String channelId, ICommandProcessor processor) {
        if(processor == null) CUSTOM_PROCESSORS.remove(channelId);
        else CUSTOM_PROCESSORS.put(channelId, processor);
    }

    @Override
    public void onEvent(Event event) {
        if(event instanceof ShardMonitorEvent) {
            if(MantaroBot.getInstance().getShardedMantaro().getShards()[shardId].getEventManager().getLastJDAEventTimeDiff() > 30000)
                return;

            //Hey, this listener is alive! (This won't pass if somehow this is blocked)
            ((ShardMonitorEvent) event).alive(shardId, ShardMonitorEvent.COMMAND_LISTENER);

            return;
        }

        if (event instanceof GuildMessageReceivedEvent) {
            GuildMessageReceivedEvent msg = (GuildMessageReceivedEvent) event;
            //Inserts a cached message into the cache. This only holds the id and the content, and is way lighter than saving the entire jda object.
            messageCache.put(msg.getMessage().getId(), Optional.of(new CachedMessage(msg.getAuthor().getIdLong(), msg.getMessage().getContentRaw())));

            //Ignore myself and bots.
            if (msg.getAuthor().isBot() || msg.getAuthor().equals(msg.getJDA().getSelfUser()))
                return;

            shard.getCommandPool().execute(() -> onCommand(msg));
        }
    }

    private void onCommand(GuildMessageReceivedEvent event) {
        try {
            Member self = event.getGuild().getSelfMember();
            if (!self.getPermissions(event.getChannel()).contains(Permission.MESSAGE_WRITE) && !self.hasPermission(Permission.ADMINISTRATOR))
                return;

            if (CUSTOM_PROCESSORS.getOrDefault(event.getChannel().getId(), commandProcessor).run(event)) {
                commandTotal++;
            } else {
                //Only run experience if no command has been executed, avoids weird race conditions when saving player status.
                try {
                    //Only run experience if the user is not rate limited (clears every 30 seconds)
                    if(random.nextInt(15) > 7 && !event.getAuthor().isBot() && experienceRatelimiter.process(event.getAuthor())) {
                        if(event.getMember() == null)
                            return;

                        Player player = MantaroData.db().getPlayer(event.getAuthor());
                        PlayerData data = player.getData();
                        DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                        GuildData guildData = dbGuild.getData();

                        if (player.isLocked())
                            return;

                        // ---------- GLOBAL EXPERIENCE CHECK ---------- //

                        //Set level to 1 if level is zero.
                        if(player.getLevel() == 0)
                            player.setLevel(1);

                        //Set player experience to a random number between 1 and 5.
                        data.setExperience(data.getExperience() + Math.round(random.nextInt(5)));

                        //Apply some black magic.
                        if(data.getExperience() > (player.getLevel() * Math.log10(player.getLevel()) * 1000) + (50 * player.getLevel() / 2)) {
                            player.setLevel(player.getLevel() + 1);

                            //Check if the member is not null, just to be sure it happened in-between.
                            if(player.getLevel() > 1 && event.getGuild().getMemberById(player.getUserId()) != null) {
                                if(guildData.isEnabledLevelUpMessages()) {
                                    String levelUpChannel = guildData.getLevelUpChannel();
                                    String levelUpMessage = guildData.getLevelUpMessage();

                                    //Player has leveled up!
                                    if(levelUpMessage != null && levelUpChannel != null) {
                                        processMessage(String.valueOf(player.getLevel()), levelUpMessage, levelUpChannel, event);
                                    }
                                }
                            }
                        }

                        //This time, actually remember to save the player so you don't have to restart 102 shards to fix it.
                        player.saveAsync();

                        // ---------- LOCAL EXPERIENCE CHECK ---------- //
                        //TODO enable in 4.9, maybe improve?

                        /*
                        LocalExperienceData localPlayer = null;
                        List<LocalExperienceData> players = guildData.getLocalPlayerExperience();

                        for(LocalExperienceData localData : players) {
                            if(localData.getUserId().equals(event.getAuthor().getId())) {
                                localPlayer = localData;
                            }
                        }

                        if(localPlayer == null) {
                            localPlayer = new LocalExperienceData(event.getAuthor().getId());
                            players.add(localPlayer);
                        }

                        if(localPlayer.getLevel() == 0)
                            localPlayer.setLevel(1);

                        localPlayer.setExperience(localPlayer.getExperience() + Math.round(random.nextInt(5)));
                        if(localPlayer.getExperience() > (localPlayer.getLevel() * Math.log10(localPlayer.getLevel()) * 1000) + (50 * localPlayer.getLevel() / 2)) {
                            localPlayer.setLevel(player.getLevel() + 1);
                        }

                        //Save local player.
                        dbGuild.saveAsync();
                       */
                    }
                } catch(Exception ignored) { }
            }
        } catch (IndexOutOfBoundsException e) {
            event.getChannel().sendMessage(
                EmoteReference.ERROR + "Your query returned no results or you used the incorrect arguments, seemingly. Just in case, check command help!")
                .queue();
        } catch (PermissionException e) {
            if (e.getPermission() != Permission.UNKNOWN) {
                event.getChannel().sendMessage(String
                    .format("%sI don't have permission to do this :<, I need the permission: **%s**%s", EmoteReference.ERROR, e.getPermission().getName(),
                        e.getMessage() != null ? String.format(" | Message: %s", e.getMessage()) : ""
                    )).queue();
            } else {
                event.getChannel().sendMessage(
                    EmoteReference.ERROR + "I cannot perform this action due to the lack of permission! Is the role I might be trying to assign" +
                        " higher than my role? Do I have the correct permissions/hierarchy to perform this action?").queue();
            }
        } catch (IllegalArgumentException e) { //NumberFormatException == IllegalArgumentException
            String id = Snow64.toSnow64(event.getMessage().getIdLong());
            event.getChannel().sendMessage(String.format(
                "%sI think you forgot something on the floor. (Maybe we threw it there? [Error ID: %s]... I hope we didn't)\n" +
                    "- Incorrect type arguments or the message I'm trying to send exceeds 2048 characters, Just in case, check command help!",
                EmoteReference.ERROR, id
            )).queue();
            log.warn("Exception caught and alternate message sent. We should look into this, anyway (ID: {})", id, e);
        } catch (ReqlError e) {
            //So much just went wrong...
            e.printStackTrace();
            SentryHelper.captureExceptionContext("Something seems to have broken in the db! Check this out!", e, this.getClass(), "Database");
        } catch (Exception e) {
            String id = Snow64.toSnow64(event.getMessage().getIdLong());
            event.getChannel().sendMessage(
                    String.format("%s%s\n(Error ID: `%s`)\n" +
                            "If you want, join our **support guild** (Link on `~>about`), or check out our GitHub page (/Mantaro/MantaroBot). " +
                            "Please tell them to quit exploding me and please don't forget the Error ID when reporting!",
                            EmoteReference.ERROR, boomQuotes[rand.nextInt(boomQuotes.length)], id)
            ).queue();

            SentryHelper.captureException(String.format("Unexpected Exception on Command: %s | (Error ID: ``%s``)", event.getMessage().getContentRaw(), id), e, this.getClass());
            log.error("Error happened with id: {} (Within command: {})", event.getMessage().getContentRaw(), id, e);
        }
    }

    private void processMessage(String level, String message, String channel, GuildMessageReceivedEvent event) {
        TextChannel tc = event.getGuild().getTextChannelById(channel);

        if (tc == null) {
            return;
        }

        if (message.contains("$(")) {
            message = new DynamicModifiers()
                .mapEvent("event", event)
                .set("level", level)
                .resolve(message);
        }

        int c = message.indexOf(':');
        if (c != -1) {
            String m = message.substring(0, c);
            String v = message.substring(c + 1);

            if (m.equals("embed")) {
                EmbedJSON embed;
                try {
                    embed = GsonDataManager.gson(false).fromJson('{' + v + '}', EmbedJSON.class);
                } catch (Exception ignored) {
                    tc.sendMessage(EmoteReference.ERROR2 + "The string ``{" + v + "}`` isn't a valid JSON.").queue();
                    return;
                }

                tc.sendMessage(embed.gen(event.getMember())).queue();
                return;
            }
        }

        tc.sendMessage(message).queue();
    }
}

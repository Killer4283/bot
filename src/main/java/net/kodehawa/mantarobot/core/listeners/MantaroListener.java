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

package net.kodehawa.mantarobot.core.listeners;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.*;
import net.dv8tion.jda.core.events.guild.GuildBanEvent;
import net.dv8tion.jda.core.events.guild.GuildJoinEvent;
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.core.events.guild.GuildUnbanEvent;
import net.dv8tion.jda.core.events.guild.member.GenericGuildMemberEvent;
import net.dv8tion.jda.core.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.core.events.guild.member.GuildMemberLeaveEvent;
import net.dv8tion.jda.core.events.guild.member.GuildMemberRoleAddEvent;
import net.dv8tion.jda.core.events.http.HttpRequestEvent;
import net.dv8tion.jda.core.events.message.guild.GuildMessageDeleteEvent;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.core.events.message.guild.GuildMessageUpdateEvent;
import net.dv8tion.jda.core.exceptions.PermissionException;
import net.dv8tion.jda.core.hooks.EventListener;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.utils.commands.RateLimiter;
import net.kodehawa.mantarobot.commands.custom.EmbedJSON;
import net.kodehawa.mantarobot.commands.info.stats.manager.GuildStatsManager;
import net.kodehawa.mantarobot.commands.info.stats.manager.GuildStatsManager.LoggedEvent;
import net.kodehawa.mantarobot.core.MantaroCore;
import net.kodehawa.mantarobot.core.listeners.command.CommandListener;
import net.kodehawa.mantarobot.core.listeners.entities.CachedMessage;
import net.kodehawa.mantarobot.core.listeners.events.ShardMonitorEvent;
import net.kodehawa.mantarobot.core.shard.MantaroShard;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.ManagedDatabase;
import net.kodehawa.mantarobot.db.entities.DBGuild;
import net.kodehawa.mantarobot.db.entities.DBUser;
import net.kodehawa.mantarobot.db.entities.PremiumKey;
import net.kodehawa.mantarobot.db.entities.helpers.GuildData;
import net.kodehawa.mantarobot.log.LogUtils;
import net.kodehawa.mantarobot.utils.SentryHelper;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.data.GsonDataManager;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static net.kodehawa.mantarobot.commands.custom.Mapifier.dynamicResolve;
import static net.kodehawa.mantarobot.commands.custom.Mapifier.map;

@Slf4j
public class MantaroListener implements EventListener {
    //The regex to filter discord invites.
    public static final Pattern DISCORD_INVITE = Pattern.compile(
            "(?:discord(?:(?:\\.|.?dot.?)gg|app(?:\\.|.?dot.?)com/invite)/(?<id>" +
                    "([\\w]{10,16}|[a-zA-Z0-9]{4,8})))");
    public static final Pattern DISCORD_INVITE_2 = Pattern.compile(
            "(https?://)?discord(app(\\.|\\s*?dot\\s*?)com\\s+?/\\s+?invite\\s*?/\\s*?|(\\.|\\s*?dot\\s*?)(gg|me|io)\\s*?/\\s*?)([a-zA-Z0-9\\-_]+)"
    );
    public static final Pattern THIRD_PARTY_INVITE = Pattern.compile(
            "(https?://)?discord(\\.|\\s*?dot\\s*?)(me|io)\\s*?/\\s*?([a-zA-Z0-9\\-_]+)"
    );
    private static final Cache<String, Long> INVITES = CacheBuilder.newBuilder()
            .maximumSize(10000)
            .build();

    private static int logTotal = 0;
    private final DateFormat df = new SimpleDateFormat("HH:mm:ss");
    private final MantaroShard shard;
    private final int shardId;
    private final RateLimiter slowModeLimiter = new RateLimiter(TimeUnit.SECONDS, 5);
    private final RateLimiter spamModeLimiter = new RateLimiter(TimeUnit.SECONDS, 2, 3);
    private final ManagedDatabase db = MantaroData.db();

    public MantaroListener(int shardId, MantaroShard shard) {
        this.shardId = shardId;
        this.shard = shard;
    }

    public static String getLogTotal() {
        return String.valueOf(logTotal);
    }

    @Override
    public void onEvent(Event event) {
        if(event instanceof ShardMonitorEvent) {
            if(MantaroBot.getInstance().getShardedMantaro().getShards()[shardId].getEventManager().getLastJDAEventTimeDiff() > 30000)
                return;
            ((ShardMonitorEvent) event).alive(shardId, ShardMonitorEvent.MANTARO_LISTENER);
            return;
        }

        if(event instanceof GuildMessageReceivedEvent) {
            MantaroBot.getInstance().getStatsClient().increment("messages_received");
            GuildMessageReceivedEvent e = (GuildMessageReceivedEvent) event;
            shard.getThreadPool().execute(() -> onMessage(e));
            return;
        }

        //Log intensifies
        if(event instanceof GuildMessageUpdateEvent) {
            shard.getThreadPool().execute(() -> logEdit((GuildMessageUpdateEvent) event));
            return;
        }

        if(event instanceof GuildMessageDeleteEvent) {
            shard.getThreadPool().execute(() -> logDelete((GuildMessageDeleteEvent) event));
            return;
        }

        if(event instanceof GuildMemberJoinEvent) {
            shard.getThreadPool().execute(() -> onUserJoin((GuildMemberJoinEvent) event));
            return;
        }

        if(event instanceof GuildMemberLeaveEvent) {
            shard.getThreadPool().execute(() -> onUserLeave((GuildMemberLeaveEvent) event));
            return;
        }

        if(event instanceof GuildUnbanEvent) {
            logUnban((GuildUnbanEvent) event);
            return;
        }

        if(event instanceof GuildBanEvent) {
            logBan((GuildBanEvent) event);
            return;
        }


        //Internal events
        if(event instanceof GuildJoinEvent) {
            GuildJoinEvent e = (GuildJoinEvent) event;
            if(e.getGuild().getSelfMember().getJoinDate().isBefore(OffsetDateTime.now().minusSeconds(30))) return;

            onJoin(e);
            MantaroBot.getInstance().getStatsClient().gauge("guilds", MantaroBot.getInstance().getGuildCache().size());
            return;
        }

        if(event instanceof GuildLeaveEvent) {
            onLeave((GuildLeaveEvent) event);
            MantaroBot.getInstance().getStatsClient().gauge("guilds", MantaroBot.getInstance().getGuildCache().size());
        }

        //debug
        if(event instanceof StatusChangeEvent) {
            logStatusChange((StatusChangeEvent) event);
        }

        if(event instanceof DisconnectEvent) {
            onDisconnect((DisconnectEvent) event);
        }

        if(event instanceof ExceptionEvent) {
            MantaroBot.getInstance().getStatsClient().increment("exceptions");
            onException((ExceptionEvent) event);
        }

        if(event instanceof HttpRequestEvent) {
            MantaroBot.getInstance().getStatsClient().incrementCounter("http_requests");
        }

        if(event instanceof ReconnectedEvent){
            MantaroBot.getInstance().getStatsClient().increment("shard.reconnect");
            MantaroBot.getInstance().getStatsClient().recordEvent(com.timgroup.statsd.Event.builder().withTitle("shard.reconnect")
                    .withText("Shard reconnected")
                    .withDate(new Date()).build());
        }

        if(event instanceof ResumedEvent) {
            MantaroBot.getInstance().getStatsClient().increment("shard.resume");
            MantaroBot.getInstance().getStatsClient().recordEvent(com.timgroup.statsd.Event.builder().withTitle("shard.resume")
                    .withText("Shard resumed")
                    .withDate(new Date()).build());
        }

        if(event instanceof GuildMemberRoleAddEvent){
            onRoleAdd((GuildMemberRoleAddEvent) event);
        }
    }

    /**
     * Handles automatic deliver of patreon keys. Should only deliver keys when
     * - An user just joined the guild and got the "Patreon" role assigned by the Patreon bot
     * - The user hasn't re-joined to get the role re-assigned
     * - The user hasn't received any keys
     * - The user pledged, obviously
     * @param event The event that says that a role got added, obv.
     */
    private void onRoleAdd(GuildMemberRoleAddEvent event){
        //Only in mantaro's guild...
        if(event.getGuild().getIdLong() == 213468583252983809L && !MantaroData.config().get().isPremiumBot) {
            User user = event.getUser();
            //who...
            DBUser dbUser = db.getUser(user);
            if(event.getRoles().stream().anyMatch(r -> r.getId().equals("290257037072531466"))) {
                //Thanks lombok for the meme names
                if (!dbUser.getData().isHasReceivedFirstKey()) {
                    //Attempt to open a PM and send a key!
                    user.openPrivateChannel().queue(channel -> {
                        //Sellout message :^)
                        channel.sendMessage(EmoteReference.EYES + "Thanks you for donating, we'll deliver your premium key shortly! :heart:").queue(message -> {
                            message.editMessage(EmoteReference.POPPER + "You received a premium key due to your donation to mantaro. " +
                                    "If any doubts, please contact Kodehawa#3457.\n" +
                                    "Instructions: **Apply this key to yourself!**. " +
                                    "This key is a 365-day long subscription to Mantaro Premium. If you want more keys (>$2 donation) " +
                                    "or want to enable the patreon bot (>$4 donation) you need to contact Kodehawa to deliver your keys.\n" +
                                    "To apply this key, run the following command in any channel `~>activatekey " +
                                    PremiumKey.generatePremiumKey(user.getId(), PremiumKey.Type.USER).getId() + "`\n" +
                                    "Thanks you soo much for donating and helping to keep mantaro alive! :heart:").queue(sent -> {
                                        dbUser.getData().setHasReceivedFirstKey(true);
                                        dbUser.saveAsync();
                                    }
                            );

                            //Celebrate internally! \ o /
                            LogUtils.log("Delivered premium key to " + user.getName() + "#" + user.getDiscriminator() + "(" + user.getId() + ")");
                        });
                    }, failure -> LogUtils.log(String.format("User: %s (%s#%s) couldn't receive the key, apply manually when asked!", user.getId(), user.getName(), user.getDiscriminator())));
                }
            }
        }
    }

    private void logBan(GuildBanEvent event) {
        String hour = df.format(new Date(System.currentTimeMillis()));
        String logChannel = MantaroData.db().getGuild(event.getGuild()).getData().getGuildLogChannel();
        if(logChannel != null) {
            TextChannel tc = event.getGuild().getTextChannelById(logChannel);
            if(tc != null){
                tc.sendMessage
                        (EmoteReference.WARNING + "`[" + hour + "]` " + event.getUser().getName() + "#" + event.getUser().getDiscriminator() + " just got banned.").queue();
                logTotal++;
            }
        }
    }

    private void logDelete(GuildMessageDeleteEvent event) {
        try {
            String hour = df.format(new Date(System.currentTimeMillis()));
            String logChannel = MantaroData.db().getGuild(event.getGuild()).getData().getGuildLogChannel();


            if(logChannel != null) {
                TextChannel tc = event.getGuild().getTextChannelById(logChannel);
                CachedMessage deletedMessage = CommandListener.getMessageCache().get(event.getMessageId(), Optional::empty).orElse(null);


                if(deletedMessage != null && !deletedMessage.getContent().isEmpty() && !event.getChannel().getId().equals(logChannel) && !deletedMessage.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                    if(MantaroData.db().getGuild(event.getGuild()).getData().getModlogBlacklistedPeople().contains(deletedMessage.getAuthor().getId())) {
                        return;
                    }

                    if(MantaroData.db().getGuild(event.getGuild()).getData().getLogExcludedChannels().contains(event.getChannel().getId())) {
                        return;
                    }

                    logTotal++;
                    tc.sendMessage(String.format(EmoteReference.WARNING + "`[%s]` Message created by **%s#%s** in channel **%s** was deleted.\n" +
                            "```diff\n-%s```", hour, deletedMessage.getAuthor().getName(), deletedMessage.getAuthor().getDiscriminator(), event.getChannel().getName(), deletedMessage.getContent().replace("```", ""))).queue();
                }
            }
        } catch(Exception e) {
            if(!(e instanceof IllegalArgumentException) && !(e instanceof NullPointerException) && !(e instanceof CacheLoader.InvalidCacheLoadException) && !(e instanceof PermissionException)) {
                log.warn("Unexpected exception while logging a deleted message.", e);
            }
        }
    }

    private void logEdit(GuildMessageUpdateEvent event) {
        try {
            String hour = df.format(new Date(System.currentTimeMillis()));
            String logChannel = MantaroData.db().getGuild(event.getGuild()).getData().getGuildLogChannel();

            if(logChannel != null) {
                TextChannel tc = event.getGuild().getTextChannelById(logChannel);
                User author = event.getAuthor();
                CachedMessage editedMessage = CommandListener.getMessageCache().get(event.getMessage().getId(), Optional::empty).orElse(null);

                if(editedMessage != null && !editedMessage.getContent().isEmpty() && !event.getChannel().getId().equals(logChannel)) {

                    if(MantaroData.db().getGuild(event.getGuild()).getData().getLogExcludedChannels().contains(event.getChannel().getId())) {
                        return;
                    }

                    if(MantaroData.db().getGuild(event.getGuild()).getData().getModlogBlacklistedPeople().contains(editedMessage.getAuthor().getId())) {
                        return;
                    }

                    tc.sendMessage(String.format(EmoteReference.WARNING + "`[%s]` Message created by **%s#%s** in channel **%s** was modified.\n```diff\n-%s\n+%s```",
                            hour, author.getName(), author.getDiscriminator(), event.getChannel().getName(), editedMessage.getContent().replace("```", ""), event.getMessage().getContent().replace("```", ""))).queue();
                    CommandListener.getMessageCache().put(event.getMessage().getId(), Optional.of(new CachedMessage(event.getAuthor().getIdLong(), event.getMessage().getContent())));
                    logTotal++;
                }
            }
        } catch(Exception e) {
            if(!(e instanceof NullPointerException) && !(e instanceof IllegalArgumentException) && !(e instanceof CacheLoader.InvalidCacheLoadException) && !(e instanceof PermissionException)) {
                log.warn("Unexpected error while logging a edit.", e);
            }
        }
    }

    private void logStatusChange(StatusChangeEvent event) {
        JDA jda = event.getJDA();
        if(jda.getShardInfo() == null) return;

        if(event.getStatus().equals(JDA.Status.CONNECTED)) {
            MantaroBot.getInstance().getStatsClient().increment("shard.connect");
            MantaroBot.getInstance().getStatsClient().recordEvent(com.timgroup.statsd.Event.builder().withTitle("shard.connected")
                    .withText("Shard connected")
                    .withDate(new Date()).build());
        }

        log.info(String.format("`Shard #%d`: Changed from `%s` to `%s`", jda.getShardInfo().getShardId(), event.getOldStatus(), event.getStatus()));
    }
    //endregion

    private void logUnban(GuildUnbanEvent event) {
        try {
            String hour = df.format(new Date(System.currentTimeMillis()));
            String logChannel = MantaroData.db().getGuild(event.getGuild()).getData().getGuildLogChannel();
            if(logChannel != null) {
                TextChannel tc = event.getGuild().getTextChannelById(logChannel);
                if(tc != null){
                    tc.sendMessage(String.format(EmoteReference.WARNING + "`[%s]` %s#%s just got unbanned.", hour, event.getUser().getName(), event.getUser().getDiscriminator())).queue();
                    logTotal++;
                }
            }
        } catch(Exception e) {
            if(!(e instanceof NullPointerException) && !(e instanceof IllegalArgumentException)) {
                log.warn("Unexpected error while logging a edit.", e);
            }
        }
    }

    //region minn
    private void onDisconnect(DisconnectEvent event) {
        if(event.isClosedByServer()) {
            log.warn(String.format("---- DISCONNECT [SERVER] CODE: [%d] %s%n",
                    event.getServiceCloseFrame().getCloseCode(), event.getCloseCode()
            ));
        } else {
            log.warn(String.format("---- DISCONNECT [CLIENT] CODE: [%d] %s%n",
                    event.getClientCloseFrame().getCloseCode(), event.getClientCloseFrame().getCloseReason()
            ));
        }
    }

    private void onException(ExceptionEvent event) {
        if(!event.isLogged()) {
            SentryHelper.captureException("Exception captured in un-logged trace", event.getCause(), this.getClass());
        }
    } //endregion

    private void onJoin(GuildJoinEvent event) {
        try {
            if(MantaroData.db().getMantaroData().getBlackListedGuilds().contains(event.getGuild().getId())
                    || MantaroData.db().getMantaroData().getBlackListedUsers().contains(
                    event.getGuild().getOwner().getUser().getId())) {
                event.getGuild().leave().queue();
                return;
            }

            MantaroBot.getInstance().getStatsClient().increment("guild_join");
            GuildStatsManager.log(LoggedEvent.JOIN);
        } catch(Exception e) {
            if(!(e instanceof NullPointerException) && !(e instanceof IllegalArgumentException)) {
                SentryHelper.captureException("Unexpected error while logging an event", e, this.getClass());
            }
        }
    }

    private void onLeave(GuildLeaveEvent event) {
        try {
            MantaroBot.getInstance().getStatsClient().increment("guild_leave");
            MantaroBot.getInstance().getAudioManager().getMusicManagers().remove(event.getGuild().getId());
            GuildStatsManager.log(LoggedEvent.LEAVE);
        } catch(Exception e) {
            if(!(e instanceof NullPointerException) && !(e instanceof IllegalArgumentException)) {
                SentryHelper.captureException("Unexpected error while logging an event", e, this.getClass());
            }
        }
    }

    private void onMessage(GuildMessageReceivedEvent event) {
        //Moderation features
        DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
        GuildData guildData = dbGuild.getData();


        //link protection
        if(guildData.isLinkProtection() && !guildData.getLinkProtectionAllowedChannels().contains(event.getChannel().getId())) {
            if(event.getMember() != null && !event.getMember().hasPermission(Permission.ADMINISTRATOR) && !event.getMember().hasPermission(Permission.MANAGE_SERVER)
                    && hasInvite(event.getJDA(), event.getGuild(), event.getMessage().getRawContent())) {
                Member bot = event.getGuild().getSelfMember();

                MantaroBot.getInstance().getStatsClient().increment("links_blocked");
                if(bot.hasPermission(Permission.MESSAGE_MANAGE) || bot.hasPermission(Permission.ADMINISTRATOR)) {
                    User author = event.getAuthor();

                    //Ignore myself.
                    if(event.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                        return;
                    }

                    //Ignore log channel.
                    if(guildData.getGuildLogChannel() != null && event.getChannel().getId().equals(guildData.getGuildLogChannel())) {
                        return;
                    }

                    //Yes, I know the check previously done is redundant, but in case someone decides to change the law of nature, it should do	.

                    event.getMessage().delete().queue();
                    event.getChannel().sendMessage(EmoteReference.ERROR + "**You cannot advertise here.** Deleted invite link sent by **" + author.getName() + "#" + author.getDiscriminator() + "**.").queue();
                } else {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "I cannot remove the invite link because I don't have permission to delete messages!").queue();
                }
            }
        }

        //Slow mode
        if(guildData.isSlowMode()) {
            if(!slowModeLimiter.process(event.getGuild().getId() + ":" + event.getAuthor().getId())) {
                Member bot = event.getGuild().getSelfMember();
                if(bot.hasPermission(Permission.MESSAGE_MANAGE) || bot.hasPermission(Permission.ADMINISTRATOR)
                        && !event.getMember().hasPermission(Permission.ADMINISTRATOR) && !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
                    event.getMessage().delete().queue();
                } else {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "I cannot engage slow mode because I don't have permission to delete messages!").queue();
                    guildData.setSlowMode(false);
                    dbGuild.save();
                    event.getChannel().sendMessage(EmoteReference.WARNING + "**Disabled slowmode due to a lack of permissions.**").queue();
                }
            }
        }

        //Anti-spam. Allows 2 messages every 3 seconds.
        if(guildData.isAntiSpam()) {
            if(!spamModeLimiter.process(event.getGuild().getId() + ":" + event.getAuthor().getId())) {
                Member bot = event.getGuild().getSelfMember();
                if(bot.hasPermission(Permission.MESSAGE_MANAGE) || bot.hasPermission(Permission.ADMINISTRATOR)
                        && !event.getMember().hasPermission(Permission.ADMINISTRATOR) && !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
                    event.getMessage().delete().queue();
                } else {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "I cannot engage anti-spam mode because I don't have permission to delete messages!").queue();
                    guildData.setAntiSpam(false);
                    dbGuild.save();
                    event.getChannel().sendMessage(EmoteReference.WARNING + "**Disabled anti-spam mode due to a lack of permissions.**").queue();
                }
            }
        }
    }

    private static boolean hasInvite(JDA jda, Guild guild, String message) {
        if(THIRD_PARTY_INVITE.matcher(message).find()) return true;
        Matcher m = DISCORD_INVITE_2.matcher(message);
        if(!m.find()) return false;
        String invite = m.group(0);
        String code = invite.substring(invite.lastIndexOf('/')+1).trim();
        System.out.println("Code> " + code);
        try {
            return INVITES.get(code, ()->Invite.resolve(jda, code).complete().getGuild().getIdLong()) != guild.getIdLong();
        } catch(ExecutionException e) {
            log.error("Error running invite validator", e);
            return DISCORD_INVITE.matcher(message).find();
        }
    }

    private void onUserJoin(GuildMemberJoinEvent event) {
        try {
            String role = MantaroData.db().getGuild(event.getGuild()).getData().getGuildAutoRole();
            DBGuild dbg = MantaroData.db().getGuild(event.getGuild());
            GuildData data = dbg.getData();

            String hour = df.format(new Date(System.currentTimeMillis()));
            if(role != null) {
                try {
                    if(event.getGuild().getRoleById(role) != null) {
                        event.getGuild().getController().addRolesToMember(event.getMember(), event.getGuild().getRoleById(role)).queue(s ->
                                log.debug("Successfully added a new role to " + event.getMember()));
                    }
                } catch(Exception e) {
                    MantaroData.db().getGuild(event.getGuild()).getData().setGuildAutoRole(null);
                    MantaroData.db().getGuild(event.getGuild()).saveAsync();
                }
            }

            String logChannel = MantaroData.db().getGuild(event.getGuild()).getData().getGuildLogChannel();
            if(logChannel != null) {
                TextChannel tc = event.getGuild().getTextChannelById(logChannel);
                tc.sendMessage(String.format("`[%s]` \uD83D\uDCE3 `%s#%s` just joined `%s` `(User #%d | ID:%s)`", hour, event.getMember().getEffectiveName(), event.getMember().getUser().getDiscriminator(), event.getGuild().getName(), event.getGuild().getMembers().size(), event.getGuild().getId())).queue();
                logTotal++;
            }

            String joinChannel = data.getLogJoinLeaveChannel();
            String joinMessage = data.getJoinMessage();

            sendJoinLeaveMessage(event, joinMessage, joinChannel);
        } catch(Exception e) {
            //This is making me go crazy already...
            SentryHelper.captureExceptionContext("Failed to send join message!", e, MantaroListener.class, "Join Handler");
        }
    }

    private void onUserLeave(GuildMemberLeaveEvent event) {
        try {
            String hour = df.format(new Date(System.currentTimeMillis()));
            DBGuild dbg = MantaroData.db().getGuild(event.getGuild());
            GuildData data = dbg.getData();


            String logChannel = MantaroData.db().getGuild(event.getGuild()).getData().getGuildLogChannel();
            if(logChannel != null) {
                TextChannel tc = event.getGuild().getTextChannelById(logChannel);
                tc.sendMessage("`[" + hour + "]` " + "\uD83D\uDCE3 `" + event.getMember().getEffectiveName() + "#" + event.getMember().getUser().getDiscriminator() + "` just left `" + event.getGuild().getName() + "` `(User #" + event.getGuild().getMembers().size() + ")`").queue();
                logTotal++;
            }

            String leaveChannel = data.getLogJoinLeaveChannel();
            String leaveMessage = data.getLeaveMessage();

            sendJoinLeaveMessage(event, leaveMessage, leaveChannel);
        } catch(Exception e) {
            SentryHelper.captureExceptionContext("Failed to send leave message!", e, MantaroListener.class, "Join Handler");
        }
    }

    private void sendJoinLeaveMessage(GenericGuildMemberEvent event, String message, String channel) {
        if(channel != null && message != null) {
            TextChannel tc = event.getGuild().getTextChannelById(channel);

            if(message.contains("$(")) {
                Map<String, String> dynamicMap = new HashMap<>();
                map("event", dynamicMap, event);
                message = dynamicResolve(message, dynamicMap);
            }

            int c = message.indexOf(':');
            if(c != -1) {
                String m = message.substring(0, c);
                String v = message.substring(c + 1);

                if(m.equals("embed")) {
                    EmbedJSON embed;
                    try {
                        embed = GsonDataManager.gson(false).fromJson('{' + v + '}', EmbedJSON.class);
                    } catch(Exception ignored) {
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
}

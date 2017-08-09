package net.kodehawa.mantarobot.core.listeners;

import com.google.common.cache.CacheLoader;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.DisconnectEvent;
import net.dv8tion.jda.core.events.Event;
import net.dv8tion.jda.core.events.ExceptionEvent;
import net.dv8tion.jda.core.events.StatusChangeEvent;
import net.dv8tion.jda.core.events.guild.GuildBanEvent;
import net.dv8tion.jda.core.events.guild.GuildJoinEvent;
import net.dv8tion.jda.core.events.guild.GuildLeaveEvent;
import net.dv8tion.jda.core.events.guild.GuildUnbanEvent;
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
import net.kodehawa.mantarobot.commands.currency.RateLimiter;
import net.kodehawa.mantarobot.commands.custom.EmbedJSON;
import net.kodehawa.mantarobot.commands.info.GuildStatsManager;
import net.kodehawa.mantarobot.commands.info.GuildStatsManager.LoggedEvent;
import net.kodehawa.mantarobot.core.LoadState;
import net.kodehawa.mantarobot.core.ShardMonitorEvent;
import net.kodehawa.mantarobot.core.listeners.command.CommandListener;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.ManagedDatabase;
import net.kodehawa.mantarobot.db.entities.DBGuild;
import net.kodehawa.mantarobot.db.entities.DBUser;
import net.kodehawa.mantarobot.db.entities.PremiumKey;
import net.kodehawa.mantarobot.db.entities.helpers.GuildData;
import net.kodehawa.mantarobot.db.entities.helpers.UserData;
import net.kodehawa.mantarobot.log.LogUtils;
import net.kodehawa.mantarobot.shard.MantaroShard;
import net.kodehawa.mantarobot.utils.SentryHelper;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.data.GsonDataManager;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static net.kodehawa.mantarobot.commands.custom.Mapifier.dynamicResolve;
import static net.kodehawa.mantarobot.commands.custom.Mapifier.map;

@Slf4j
public class MantaroListener implements EventListener {
    public static final Pattern DISCORD_INVITE = Pattern.compile(
            "(?:discord(?:(?:\\.|.?dot.?)gg|app(?:\\.|.?dot.?)com/invite)/(?<id>" +
                    "([\\w]{10,16}|[a-zA-Z0-9]{4,8})))");
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

        if(!MantaroBot.loadState.equals(LoadState.POSTLOAD)) return;

        if(event instanceof ShardMonitorEvent) {
            if(MantaroBot.getInstance().getShardedMantaro().getShards()[shardId].getEventManager().getLastJDAEventTimeDiff() > 120000)
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
            MantaroBot.getInstance().getStatsClient().gauge("guilds", MantaroBot.getInstance().getGuilds().size());
            return;
        }

        if(event instanceof GuildLeaveEvent) {
            onLeave((GuildLeaveEvent) event);
            MantaroBot.getInstance().getStatsClient().gauge("guilds", MantaroBot.getInstance().getGuilds().size());
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
            onHttpRequest((HttpRequestEvent) event);
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
        User user = event.getUser();
        DBUser dbUser = db.getUser(user);
        if(event.getRoles().stream().anyMatch(r -> r.getId().equals("331250898590367744"))) {
            //Thanks lombok for the meme names
            if (!dbUser.getData().isHasReceivedFirstKey() && !MantaroData.config().get().isPremiumBot()) {
                //Attempt to open a PM and send a key!
                user.openPrivateChannel().queue(channel -> {
                    channel.sendMessage(EmoteReference.EYES + "Thanks you for donating, we'll deliver your premium key shortly! :heart:").queue(message -> {
                        message.editMessage(EmoteReference.POPPER + "You received a premium key due to your donation to mantaro. If any doubts, please contact Kodehawa#3457.\n" +
                                "Instructions: **Apply this key to yourself!**. This key is a 365-day long subscription to Mantaro Premium. If you want more keys (>$2 donation) " +
                                "or want to enable the patreon bot (>$4 donation) you need to contact Kodehawa to deliver your keys.\n" +
                                "To apply this key, run the following command in any channel `~>activatekey " +
                                PremiumKey.generatePremiumKey(user.getId(), PremiumKey.Type.USER) + " user`\n" +
                                "Thanks you soo much for donating and helping to keep mantaro alive! :heart:").queue(sent -> {
                                    dbUser.getData().setHasReceivedFirstKey(true);
                                    dbUser.saveAsync();
                                }
                        );
                    });
                }, failure -> LogUtils.log(String.format("User: %s (%s#%s) couldn't receive the key, apply manually when asked!", user.getId(), user.getName(), user.getDiscriminator())));
            }
        }
    }

    private void logBan(GuildBanEvent event) {
        String hour = df.format(new Date(System.currentTimeMillis()));
        String logChannel = MantaroData.db().getGuild(event.getGuild()).getData().getGuildLogChannel();
        if(logChannel != null) {
            TextChannel tc = event.getGuild().getTextChannelById(logChannel);
            tc.sendMessage
                    (EmoteReference.WARNING + "`[" + hour + "]` " + event.getUser().getName() + "#" + event.getUser().getDiscriminator() + " just got banned.").queue();
            logTotal++;
        }
    }


    public void onHttpRequest(HttpRequestEvent event) {
        MantaroBot.getInstance().getStatsClient().incrementCounter("http_requests");
        try {
            if(!event.getResponse().isOk()) {
                System.out.println("--------------------");
                System.out.println("HTTP Request");
                System.out.println(event.getRoute().getMethod() + " /" + event.getRoute().getCompiledRoute());
                System.out.println(event.getRequestHeaders().toString().replace(event.getJDA().getToken(), "[TOKEN]"));
                if(event.getRequestBodyRaw() != null) System.out.println(event.getRequestBodyRaw());
                System.out.println("Response code: " + event.getResponseRaw().code());
                System.out.println("--------------------");
                MantaroBot.getInstance().getStatsClient().recordEvent(com.timgroup.statsd.Event.builder().withTitle("Failed HTTP request")
                        .withText(event.getRoute().getMethod() + " /" + event.getRoute().getCompiledRoute() + " | Response code: " + event.getResponseRaw().code())
                        .withDate(new Date()).build());
            }
        } catch(Exception ignored) {
        }
    }

    private void logDelete(GuildMessageDeleteEvent event) {
        try {
            String hour = df.format(new Date(System.currentTimeMillis()));
            String logChannel = MantaroData.db().getGuild(event.getGuild()).getData().getGuildLogChannel();


            if(logChannel != null) {
                TextChannel tc = event.getGuild().getTextChannelById(logChannel);
                Message deletedMessage = CommandListener.getMessageCache().get(event.getMessageId(), Optional::empty).orElse(null);


                if(deletedMessage != null && !deletedMessage.getContent().isEmpty() && !event.getChannel().getId().equals(logChannel) && !deletedMessage.getAuthor().getId().equals(event.getJDA().getSelfUser().getId())) {
                    if(MantaroData.db().getGuild(event.getGuild()).getData().getModlogBlacklistedPeople().contains(deletedMessage.getAuthor().getId())) {
                        return;
                    }

                    if(MantaroData.db().getGuild(event.getGuild()).getData().getLogExcludedChannels().contains(deletedMessage.getChannel().getId())) {
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
                Message editedMessage = CommandListener.getMessageCache().get(event.getMessage().getId(), Optional::empty).orElse(null);

                if(editedMessage != null && !editedMessage.getContent().isEmpty() && !event.getChannel().getId().equals(logChannel)) {

                    if(MantaroData.db().getGuild(event.getGuild()).getData().getLogExcludedChannels().contains(editedMessage.getChannel().getId())) {
                        return;
                    }

                    if(MantaroData.db().getGuild(event.getGuild()).getData().getModlogBlacklistedPeople().contains(editedMessage.getAuthor().getId())) {
                        return;
                    }

                    tc.sendMessage(String.format(EmoteReference.WARNING + "`[%s]` Message created by **%s#%s** in channel **%s** was modified.\n```diff\n-%s\n+%s```",
                            hour, author.getName(), author.getDiscriminator(), event.getChannel().getName(), editedMessage.getContent().replace("```", ""), event.getMessage().getContent().replace("```", ""))).queue();
                    CommandListener.getMessageCache().put(event.getMessage().getId(), Optional.of(event.getMessage()));
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

        if(event.getStatus().equals(JDA.Status.ATTEMPTING_TO_RECONNECT)) {
            MantaroBot.getInstance().getStatsClient().increment("shard.reconnect");
            MantaroBot.getInstance().getStatsClient().recordEvent(com.timgroup.statsd.Event.builder().withTitle("shard.reconnect")
                    .withText("Shard reconnecting")
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
                tc.sendMessage(String.format(EmoteReference.WARNING + "`[%s]` %s#%s just got unbanned.", hour, event.getUser().getName(), event.getUser().getDiscriminator())).queue();
                logTotal++;
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
            if(DISCORD_INVITE.matcher(event.getMessage().getContent()).find()
                    && !event.getMember().hasPermission(Permission.ADMINISTRATOR) && !event.getMember().hasPermission(Permission.MANAGE_SERVER)) {
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


        //Birthday role checker.
        try {
            Role birthdayRole = event.getGuild().getRoleById(MantaroData.db().getGuild(event.getGuild()).getData().getBirthdayRole());
            UserData user = MantaroData.db().getUser(event.getMember()).getData();
            if(birthdayRole != null && user.getBirthday() != null) {
                TextChannel channel = event.getGuild().getTextChannelById(MantaroData.db().getGuild(event.getGuild()).getData().getBirthdayChannel());
                Calendar cal = Calendar.getInstance();
                SimpleDateFormat dateFormat = new SimpleDateFormat("dd-MM-yyyy");
                if(user.getBirthday().substring(0, 5).equals(dateFormat.format(cal.getTime()).substring(0, 5))) {
                    if(!event.getMember().getRoles().contains(birthdayRole)) {
                        event.getGuild().getController().addSingleRoleToMember(event.getMember(), birthdayRole).queue(s -> {
                                    channel.sendMessage(String.format(EmoteReference.POPPER + "**%s is a year older now! Wish them a happy birthday.** :tada:",
                                            event.getMember().getEffectiveName())).queue();
                                    MantaroBot.getInstance().getStatsClient().increment("birthdays_logged");
                                }
                        );
                    }
                } else {
                    if(event.getGuild().getRoles().contains(birthdayRole)) {
                        event.getGuild().getController().removeRolesFromMember(event.getMember(), birthdayRole).queue();
                    }
                }
            }
        } catch(Exception e) {
            if(e instanceof PermissionException) {
                resetBirthdays(event.getGuild());
                event.getChannel().sendMessage(EmoteReference.ERROR + "Error while applying birthday role, so the role assigner will be resetted. **Remember that the bot MUST have permissions to apply roles to that person, always**").queue();
            }
            //else ignore
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
                    event.getGuild().getController().addRolesToMember(event.getMember(), event.getGuild().getRoleById(role)).queue(s ->
                            log.debug("Successfully added a new role to " + event.getMember()));
                } catch(PermissionException e) {
                    MantaroData.db().getGuild(event.getGuild()).getData().setGuildAutoRole(null);
                    MantaroData.db().getGuild(event.getGuild()).save();
                    event.getGuild().getOwner().getUser().openPrivateChannel().queue(messageChannel ->
                            messageChannel.sendMessage("Removed autorole since I don't have the permissions to assign that role").queue());
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

            if(joinChannel != null && joinMessage != null) {
                TextChannel tc = event.getGuild().getTextChannelById(joinChannel);

                if(joinMessage.contains("$(")) {
                    Map<String, String> dynamicMap = new HashMap<>();
                    map("event", dynamicMap, event);
                    joinMessage = dynamicResolve(joinMessage, dynamicMap);
                }

                int c = joinMessage.indexOf(':');
                if(c != -1) {
                    String m = joinMessage.substring(0, c);
                    String v = joinMessage.substring(c + 1);

                    if(m.equals("embed")) {
                        EmbedJSON embed;
                        try {
                            embed = GsonDataManager.gson(false).fromJson('{' + v + '}', EmbedJSON.class);
                        } catch(Exception ignored) {
                            tc.sendMessage(EmoteReference.ERROR2 + "The string ``{" + v + "}`` isn't a valid JSON.").queue();
                            return;
                        }

                        tc.sendMessage(embed.gen(event)).queue();
                        return;
                    }
                }

                tc.sendMessage(joinMessage).queue();
            }
        } catch(Exception ignored) {
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


            if(leaveChannel != null && leaveMessage != null) {
                TextChannel tc = event.getGuild().getTextChannelById(leaveChannel);

                if(leaveMessage.contains("$(")) {
                    Map<String, String> dynamicMap = new HashMap<>();
                    map("event", dynamicMap, event);
                    leaveMessage = dynamicResolve(leaveMessage, dynamicMap);
                }

                int c = leaveMessage.indexOf(':');
                if(c != -1) {
                    String m = leaveMessage.substring(0, c);
                    String v = leaveMessage.substring(c + 1);

                    if(m.equals("embed")) {
                        EmbedJSON embed;
                        try {
                            embed = GsonDataManager.gson(false).fromJson('{' + v + '}', EmbedJSON.class);
                        } catch(Exception ignored) {
                            tc.sendMessage(EmoteReference.ERROR2 + "The string ``{" + v + "}`` isn't a valid JSON.").queue();
                            return;
                        }

                        tc.sendMessage(embed.gen(event)).queue();
                        return;
                    }
                }
                tc.sendMessage(leaveMessage).queue();
            }

        } catch(Exception ignored) {
        }
    }

    private void resetBirthdays(Guild guild) {
        DBGuild data = MantaroData.db().getGuild(guild);
        data.getData().setBirthdayChannel(null);
        data.getData().setBirthdayRole(null);
        data.save();
    }
}

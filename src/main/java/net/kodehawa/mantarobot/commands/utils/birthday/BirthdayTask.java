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

package net.kodehawa.mantarobot.commands.utils.birthday;

import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import net.dv8tion.jda.api.utils.SplitUtil;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.custom.EmbedJSON;
import net.kodehawa.mantarobot.commands.custom.legacy.DynamicModifiers;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.Pair;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.data.JsonDataManager;
import net.kodehawa.mantarobot.utils.exporters.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class BirthdayTask {
    private static final Pattern MODIFIER_PATTERN = Pattern.compile("\\p{L}*:");
    private static final Logger log = LoggerFactory.getLogger(BirthdayTask.class);
    private static final DateTimeFormatter dayMonthFormat = DateTimeFormatter.ofPattern("dd-MM");
    private static final DateTimeFormatter monthFormat = DateTimeFormatter.ofPattern("MM");

    private static final String modLogMessage = "Birthday assigner." +
            " If you see this happening for every member of your server, or in unintended ways, please do ~>opts birthday disable";

    private static final ScheduledExecutorService backOffPool = Executors.newScheduledThreadPool(2,
            new ThreadFactoryBuilder().setNameFormat("Birthday Backoff Message Thread").build()
    );

    private static final ScheduledExecutorService backOffRolePool = Executors.newScheduledThreadPool(4,
            new ThreadFactoryBuilder().setNameFormat("Birthday Backoff Role Thread").build()
    );

    public static void handle(int shardId) {
        final var bot = MantaroBot.getInstance();
        final var instant = Instant.now();
        try {
            final var cache = bot.getBirthdayCacher();
            // There's no cache to be seen here
            if (cache == null) {
                return;
            }

            final var start = System.currentTimeMillis();
            var membersAssigned = 0;
            var membersDivested = 0;

            final var jda = bot.getShardManager().getShardById(shardId);
            if (jda == null) { // To be fair, this shouldn't be possible as it only starts it with the shards it knows...
                return;
            }

            log.info("Checking birthdays in shard {} to assign roles...", jda.getShardInfo().getShardId());

            // Well, fuck, this was a day off. NYC time was 23:00 when Chicago time was at 00:00, so it checked the
            // birthdays for THE WRONG DAY. Heck.
            // 17-02-2022: Fuck again, I was using the wrong thing. Now it works, lol.
            final var timezone = ZonedDateTime.ofInstant(instant, ZoneId.of("America/Chicago"));
            // Example: 25-02
            final var now = timezone.format(dayMonthFormat);
            // Example: 02
            final var month = timezone.format(monthFormat);
            // Example: 01
            final var lastMonthTz = ZonedDateTime.ofInstant(instant, ZoneId.of("America/Chicago"))
                    .minusMonths(1);
            final var lastMonth = lastMonthTz.format(monthFormat);

            final var cached = cache.getCachedBirthdays();
            final var guilds = jda.getGuildCache();

            // Backoff sending: we need to backoff the birthday requests,
            // else we're gonna find ourselves quite often hitting ratelimits, which might slow the whole
            // bot down. Therefore, we're just gonna get all of the messages we need to send and *slowly*
            // send them over the course of a few minutes, instead of trying to send them all at once.
            Map<BirthdayGuildInfo, List<MessageCreateBuilder>> toSend = new HashMap<>();
            List<BirthdayRoleInfo> roleBackoffAdd = new ArrayList<>();
            List<BirthdayRoleInfo> roleBackoffRemove = new ArrayList<>();

            // For all current -cached- guilds.
            for (final var guild : guilds) {
                // This is quite a db spam, lol
                final var dbGuild = MantaroData.db().getGuild(guild);
                final var guildLanguageContext = new I18nContext(dbGuild, null);

                // If we have a birthday guild and channel here, continue
                if (dbGuild.getBirthdayChannel() != null && dbGuild.getBirthdayRole() != null) {
                    final var birthdayRole = guild.getRoleById(dbGuild.getBirthdayRole());
                    final var channel = guild.getChannelById(StandardGuildMessageChannel.class, dbGuild.getBirthdayChannel());

                    if (channel != null && birthdayRole != null) {
                        if (!guild.getSelfMember().canInteract(birthdayRole))
                            continue; //Go to next guild...
                        if (!channel.canTalk())
                            continue; //cannot talk here...
                        if (dbGuild.getGuildAutoRole() != null && birthdayRole.getId().equals(dbGuild.getGuildAutoRole()))
                            continue; //Birthday role is autorole role
                        if (birthdayRole.isPublicRole())
                            continue; //Birthday role is public role
                        if (birthdayRole.isManaged())
                            continue; //This was meant to be a bot role?

                        // Guild map is now created from allowed birthdays. This is a little hacky, but we don't really care.
                        // The other solution would have been just disabling this completely, which would have been worse.
                        // @formatter:off
                        Map<Long, BirthdayCacher.BirthdayData> guildMap = cached.entrySet()
                                .stream()
                                .filter(map -> dbGuild.getAllowedBirthdays().contains(String.valueOf(map.getKey())))
                                .filter(map ->
                                        // Only check for current month or last month!
                                        map.getValue().birthday().substring(3, 5).equals(month) ||
                                                map.getValue().birthday().substring(3, 5).equals(lastMonth)
                                ).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
                        // @formatter:on

                        int birthdayNumber = 0;
                        List<Long> nullMembers = new ArrayList<>();
                        StringBuilder currentContent = new StringBuilder(guildLanguageContext.get("general.birthday"))
                                .append("\n\n");
                        List<String> contentList = new ArrayList<>();
                        List<MessageEmbed> embedList = new ArrayList<>();

                        for (var data : guildMap.entrySet()) {
                            var birthday = data.getValue().birthday();
                            if (dbGuild.getBirthdayBlockedIds().contains(String.valueOf(data.getKey()))) {
                                continue;
                            }

                            if (birthday == null) {
                                log.debug("Birthday is null? Continuing...");
                                nullMembers.add(data.getKey());
                                continue;
                            }

                            // This needs to be a retrieveMemberById call, sadly. This will get cached, though.
                            Member member;
                            try {
                                // This is expensive!
                                member = guild.retrieveMemberById(data.getKey()).useCache(true).complete();
                            } catch (Exception ex) {
                                nullMembers.add(data.getKey());
                                continue;
                            }

                            // Make sure we announce on March 1st for birthdays on February 29 if the current
                            // year is not a leap year.
                            var compare = birthday.substring(0, 5);
                            if (compare.equals("29-02") && !Year.isLeap(LocalDate.now().getYear())) {
                                compare = "28-02";
                            }

                            if (compare.equals(now)) {
                                log.debug("Assigning birthday role on guild {} (M: {})", guild.getId(), member.getEffectiveName());
                                var tempBirthdayMessage =
                                        String.format(EmoteReference.POPPER + "**%s is a year older now! Wish them a happy birthday.** :tada:",
                                                member.getEffectiveName());

                                if (dbGuild.getBirthdayMessage() != null) {
                                    tempBirthdayMessage = dbGuild.getBirthdayMessage()
                                            .replace("$(user)", member.getEffectiveName())
                                            .replace("$(usermention)", member.getAsMention())
                                            .replace("$(tag)", Utils.getTagOrDisplay(member.getUser())) // legacy support, discrims are technically gone
                                            .replace("$(global_name)", member.getUser().getGlobalName() == null ? "none" : member.getUser().getGlobalName());
                                }

                                // Variable used in lambda expression should be final or effectively final...
                                final var birthdayMessage = tempBirthdayMessage;
                                if (!member.getRoles().contains(birthdayRole)) {
                                    log.debug("Backing off adding birthday role on guild {} (M: {})", guild.getId(), member.getEffectiveName());

                                    // We can pretty much do all of this only based on the IDs
                                    roleBackoffAdd.add(new BirthdayRoleInfo(guild.getId(), member.getId(), birthdayRole));
                                    final Pair<String, MessageEmbed> messagePair = buildBirthdayMessage(birthdayMessage, channel, member);
                                    if (messagePair.left() != null) {
                                        try {
                                            // ensure the content itself does not exceed 2000 characters
                                            List<String> parts = SplitUtil.split(
                                                    messagePair.left(),
                                                    Message.MAX_CONTENT_LENGTH,
                                                    SplitUtil.Strategy.NEWLINE,
                                                    SplitUtil.Strategy.WHITESPACE
                                            );
                                            // only one part so it fits in a single message as ensured by SplitUtil
                                            // we proceed by checking if it fits into the current content
                                            if (parts.size() == 1) {
                                                String part = parts.get(0);
                                                // it does not fit into the current content, add the current one to the list
                                                // and create a new one
                                                if (currentContent.length() + part.length() > Message.MAX_CONTENT_LENGTH) {
                                                    contentList.add(currentContent.toString());
                                                    currentContent = new StringBuilder();
                                                }
                                                currentContent.append(part);
                                            } else {
                                                // every single of these (except the last one) parts is guaranteed to be exactly the message content length
                                                // meaning we need a new content for all of them and the last element will be used going forward
                                                String last = parts.remove(parts.size() - 1);
                                                // we have to add the current content even if it still has space, as it might
                                                // break continuity in the messages if we merge them out of order
                                                contentList.add(currentContent.toString());
                                                currentContent = new StringBuilder(last);
                                                contentList.addAll(parts);
                                            }
                                            // add a new line to separate this b-day message from the next
                                            // Note: this is going to be trimmed by discord if at the end
                                            // meaning checking length *should* not be necessary
                                            currentContent.append("\n");
                                        } catch (IllegalStateException e) {
                                            log.debug("Failed to use SplitUtil to ensure birthday message length: {}", messagePair.left());
                                            continue;
                                        }
                                    }
                                    if (messagePair.right() != null) {
                                        // add embed to list
                                        embedList.add(messagePair.right());
                                    }
                                    membersAssigned++;
                                    birthdayNumber++;

                                    Metrics.BIRTHDAY_COUNTER.inc();
                                }
                            } else {
                                //day passed
                                if (member.getRoles().contains(birthdayRole)) {
                                    log.debug("Backing off removing birthday role on guild {} (M: {})", guild.getId(), member.getEffectiveName());
                                    roleBackoffRemove.add(new BirthdayRoleInfo(guild.getId(), member.getId(), birthdayRole));
                                    membersDivested++;
                                }
                            }
                        }

                        if (birthdayNumber != 0) {
                            // add the last build content to the list if it wasn't empty
                            // \n check is here to avoid any potential "cannot send an empty message"
                            if (!currentContent.isEmpty() && !currentContent.toString().equals("\n")) {
                                contentList.add(currentContent.toString());
                            }

                            // map messages to MessageCreateBuilder
                            List<MessageCreateBuilder> builders = contentList.stream()
                                    .map(m -> new MessageCreateBuilder().addContent(m))
                                    .collect(Collectors.toList()); // list needs to be mutable

                            // partition embed list into chunks of 10
                            List<List<MessageEmbed>> embedPartition = Lists.partition(embedList, Message.MAX_EMBED_COUNT);
                            // add embeds to the first n (n = size) MessageCreateBuilder
                            for (int i = 0; i < embedPartition.size(); i++) {
                                if (i >= builders.size()) {
                                    builders.add(new MessageCreateBuilder().addEmbeds(embedPartition.get(i)));
                                } else {
                                    builders.get(i).addEmbeds(embedPartition.get(i));
                                }
                            }
                            toSend.put(new BirthdayGuildInfo(guild.getId(), channel.getId()), builders);
                        }

                        // If any of the member lookups to discord returned null, remove them.
                        if (!nullMembers.isEmpty()) {
                            nullMembers.forEach(member -> dbGuild.removeAllowedBirthday(String.valueOf(member)));
                            dbGuild.updateAllChanged();
                        }
                    }
                }
            }

            final var end = System.currentTimeMillis();
            log.info("{} (birthdays): people assigned: {}, people divested: {}, took {}ms",
                    jda.getShardInfo(), membersAssigned, membersDivested, (end - start)
            );

            // A pool inside a pool?
            // Send the backoff sending comment above, this basically avoids hitting
            // discord with one billion requests at once.
            final var backoff = 400;
            final var roleBackoff = 100;
            backOffPool.submit(() -> {
                log.info("{} (birthdays): Backoff messages: {}. Sending them with {}ms backoff.",
                        jda.getShardInfo(), toSend.size(), backoff
                );

                final var startMessage = System.currentTimeMillis();
                for (var entry : toSend.entrySet()) {
                    try {
                        final var info = entry.getKey();
                        final var guildId = info.guildId;
                        final var channelId = info.channelId;
                        final var messages = entry.getValue();

                        final var guild = bot.getShardManager().getGuildById(guildId);
                        if (guild == null)
                            continue;

                        final var channel = guild.getChannelById(StandardGuildMessageChannel.class, channelId);
                        if (channel == null)
                            continue;

                        messages.forEach(message -> channel.sendMessage(message.build())
                                .setAllowedMentions(EnumSet.of(
                                        Message.MentionType.USER, Message.MentionType.CHANNEL,
                                        Message.MentionType.ROLE, Message.MentionType.EMOJI)
                                )
                                .queue()
                        );
                        // If 100 guilds (about 1/10th of all the shard guilds! so very unlikely) do
                        // get a birthday now, the maximum delay will be 40,000ms, which is 40 seconds.
                        // Not much of an issue for the end user, but avoid sending too many requests
                        // to discord at once. If half of all the guilds in the shard do, the delay
                        // will be about 200,000ms, so 2 minutes.
                        Thread.sleep(backoff);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                final var endMessage = System.currentTimeMillis();
                toSend.clear();
                log.info("Sent all birthday backoff messages, backoff was {}ms, took {}ms", backoff, endMessage - startMessage);
            });

            backOffRolePool.submit(() -> {
                log.info("{} (birthdays): Backoff roles (add): {}. Sending them with {}ms backoff.",
                        jda.getShardInfo(), roleBackoffAdd.size(), roleBackoff
                );

                final var startRole = System.currentTimeMillis();
                for (var roleInfo : roleBackoffAdd) {
                    try {
                        var guild = bot.getShardManager().getGuildById(roleInfo.guildId);
                        if (guild == null)
                            continue;

                        guild.addRoleToMember(UserSnowflake.fromId(roleInfo.memberId), roleInfo.role)
                                .reason(modLogMessage)
                                .queue();

                        Thread.sleep(roleBackoff);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                log.info("{} (birthdays): Backoff roles (remove): {}. Sending them with {}ms backoff.",
                        jda.getShardInfo(), roleBackoffRemove.size(), roleBackoff
                );

                for (var roleInfo : roleBackoffRemove) {
                    try {
                        var guild = bot.getShardManager().getGuildById(roleInfo.guildId);
                        if (guild == null)
                            continue;

                        guild.removeRoleFromMember(UserSnowflake.fromId(roleInfo.memberId), roleInfo.role)
                                .reason(modLogMessage)
                                .queue();

                        Thread.sleep(roleBackoff);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                final var endRole = System.currentTimeMillis();
                roleBackoffAdd.clear();
                roleBackoffRemove.clear();

                log.info("{} (birthdays): All roles done (add and removal), backoff was {}ms. Took {}ms",
                        jda.getShardInfo(), roleBackoff, endRole - startRole
                );
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static Pair<String, MessageEmbed> buildBirthdayMessage(String message, StandardGuildMessageChannel channel, Member user) {
        if (message.contains("$(")) {
            message = new DynamicModifiers()
                    .mapFromBirthday("event", channel, user, channel.getGuild())
                    .resolve(message);
        }

        // copy-pasted from welcome messages
        var modIndex = message.indexOf(':');
        if (modIndex != -1) {
            // Wonky?
            var matcher = MODIFIER_PATTERN.matcher(message);
            var modifier = "none";
            // Find the first occurrence of a modifier (word:)
            if (matcher.find()) {
                modifier = matcher.group().replace(":", "");
            }

            var json = message.substring(modIndex + 1);
            var extra = "";

            // Somehow (?) this fails sometimes? I really dunno how, but sure.
            try {
                extra = message.substring(0, modIndex - modifier.length()).trim();
            } catch (Exception ignored) {
            }

            try {
                if (modifier.equals("embed")) {
                    EmbedJSON embed;
                    try {
                        embed = JsonDataManager.fromJson('{' + json + '}', EmbedJSON.class);
                    } catch (Exception e) {
                        // So I know what is going on, regardless.
                        e.printStackTrace();
                        return Pair.of(EmoteReference.ERROR2.toHeaderString() +
                                        "The string\n```json\n{" +
                                        json +
                                        "}```\n" +
                                        "Is not a valid birthday message (failed to Convert to EmbedJSON). Check the wiki for more information.\n",
                                null);
                    }

                    var messageEmbed = embed.gen(null);
                    return Pair.of(extra.isEmpty() ? null : extra + "\n", messageEmbed);
                }
            } catch (Exception e) {
                String err;
                if (e.getMessage().toLowerCase().contains("url must be a valid")) {
                    err = "Failed to send birthday message: Wrong image URL in thumbnail, image, footer and/or author.\n";
                } else {
                    err = "Failed to send birthday message: Unknown error, try checking your message.\n";
                }

                return Pair.of(err, null);
            }
        }

        // No match.
        return Pair.of(message + "\n", null);
    }

    private record BirthdayGuildInfo(String guildId, String channelId) {
    }

    private record BirthdayRoleInfo(String guildId, String memberId, Role role) {
    }
}

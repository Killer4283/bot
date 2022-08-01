/*
 * Copyright (C) 2016-2022 David Rubio Escares / Kodehawa
 *
 *  Mantaro is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  Mantaro is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro. If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.api.Permission;
import net.kodehawa.mantarobot.commands.moderation.ModLog;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.CommandCategory;
import net.kodehawa.mantarobot.core.modules.commands.base.Context;
import net.kodehawa.mantarobot.core.modules.commands.help.HelpContent;
import net.kodehawa.mantarobot.options.core.Option;
import net.kodehawa.mantarobot.options.core.OptionType;
import net.kodehawa.mantarobot.utils.StringUtils;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.CustomFinderUtil;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Module
public class MuteCmds {
    private static final Pattern rawTimePattern = Pattern.compile("^[(\\d)((?d|?h|(?m|(?s)]+$");
    private static final Pattern timePattern =
            Pattern.compile("[(\\d+)((?:h(?:our(?:s)?)?)|(?:m(?:in(?:ute(?:s)?)?)?)|(?:s(?:ec(?:ond(?:s)?)?)?))]+");
    // Regex by Fabricio20
    private static final Pattern muteTimePattern =
            Pattern.compile("-time [(\\d+)((?:h(?:our(?:s)?)?)|(?:m(?:in(?:ute(?:s)?)?)?)|(?:s(?:ec(?:ond(?:s)?)?)?))]+");

    @Subscribe
    public void mute(CommandRegistry registry) {
        SimpleCommand mute = registry.register("mute", new SimpleCommand(CommandCategory.MODERATION) {
            @Override
            protected void call(Context ctx, String content, String[] args) {
                if (args.length == 0) {
                    ctx.sendLocalized("commands.mute.no_users", EmoteReference.ERROR);
                    return;
                }

                if (!ctx.getMember().hasPermission(Permission.MODERATE_MEMBERS)) {
                    ctx.sendLocalized("commands.mute.no_permissions", EmoteReference.ERROR);
                    return;
                }

                var dbGuild = ctx.getDBGuild();
                var guildData = dbGuild.getData();
                var reason = "Not specified";
                var opts = ctx.getOptionalArguments();

                var time = guildData.getSetModTimeout() > 0 ? guildData.getSetModTimeout() : 0L;
                final var maybeTime = args[0];
                var affected = args[0];
                final var matchTime = rawTimePattern.matcher(maybeTime).matches();
                if (matchTime && args.length > 1) {
                    content = content.replace(maybeTime, "").trim();
                    affected = args[1];
                    time = Utils.parseTime(maybeTime);
                }

                if (args.length > 1) {
                    reason = StringUtils.splitArgs(content, 2)[1];
                }

                if (!ctx.getSelfMember().hasPermission(Permission.MODERATE_MEMBERS)) {
                    ctx.sendLocalized("commands.mute.no_moderate_members", EmoteReference.ERROR);
                    return;
                }

                if (opts.containsKey("time") && !matchTime) {
                    if (opts.get("time") == null || opts.get("time").isEmpty()) {
                        ctx.sendLocalized("commands.mute.time_not_specified", EmoteReference.WARNING);
                        return;
                    }

                    time = Utils.parseTime(opts.get("time"));
                }

                if (time == 0) {
                    ctx.sendLocalized("commands.mute.time_not_specified_generic", EmoteReference.ERROR);
                    return;
                }

                if (time < 10000) {
                    ctx.sendLocalized("commands.mute.time_too_little", EmoteReference.ERROR);
                    return;
                }

                if (time > TimeUnit.DAYS.toMillis(10)) {
                    ctx.sendLocalized("commands.mute.time_too_long", EmoteReference.ERROR);
                    return;
                }

                // This is funny at this point lol
                final var finalReason = muteTimePattern.matcher(reason).replaceAll("").trim();
                final var finalTime = time;
                final var finalAffected = affected;
                ctx.findMember(affected, members -> {
                    var member = CustomFinderUtil.findMember(finalAffected, members, ctx);
                    if (member == null)
                        return;

                    var user = member.getUser();
                    dbGuild.saveUpdating();

                    if (member.isTimedOut()) {
                        ctx.sendLocalized("commands.mute.already_muted", EmoteReference.WARNING);
                        return;
                    }

                    if (!ctx.getSelfMember().canInteract(member)) {
                        ctx.sendLocalized("commands.mute.self_hierarchy_error", EmoteReference.ERROR);
                        return;
                    }

                    if (!ctx.getMember().canInteract(member)) {
                        ctx.sendLocalized("commands.mute.user_hierarchy_error", EmoteReference.ERROR);
                        return;
                    }

                    member.timeoutFor(Duration.ofMillis(finalTime)).reason(
                            String.format("Muted by %#s for %s: %s", ctx.getAuthor(), Utils.formatDuration(ctx.getLanguageContext(), finalTime), finalReason)
                    ).queue();

                    if (finalReason.isEmpty()) {
                        ctx.sendLocalized("commands.mute.success", EmoteReference.CORRECT, member.getEffectiveName(), Utils.formatDuration(ctx.getLanguageContext(), finalTime));
                    } else {
                        ctx.sendLocalized("commands.mute.success_reason", EmoteReference.CORRECT,
                                member.getEffectiveName(), Utils.formatDuration(ctx.getLanguageContext(), finalTime), finalReason
                        );
                    }

                    dbGuild.getData().setCases(dbGuild.getData().getCases() + 1);
                    dbGuild.saveUpdating();

                    ModLog.log(
                            ctx.getMember(), user, finalReason, ctx.getChannel().getName(), ModLog.ModAction.MUTE, dbGuild.getData().getCases()
                    );
                });
            }

            @Override
            public HelpContent help() {
                return new HelpContent.Builder()
                        .setDescription("Mutes the specified users.")
                        .setUsage("`~>mute [time] <@user> [reason]`")
                        .addParameter("time",
                                "The time to mute an user for. For example `~>mute 1m20s @Natan#1289 wew, nice` " +
                                        "will mute Natan for 1 minute and 20 seconds.")
                        .addParameter("@user",
                                "The users to mute. Needs to be mentions (pings)")
                        .addParameter("reason",
                                "The mute reason. This is optional.")
                        .build();
            }
        });

        mute.addOption("defaultmutetimeout:set", new Option("Default mute timeout", """
                Sets the default mute timeout for ~>mute.
                This command will set the timeout of ~>mute to a fixed value **unless you specify another time in the command**
                **Example:** `~>opts defaultmutetimeout set 1m20s`
                **Considerations:** Time is in 1m20s or 1h10m3s format, for example.""", OptionType.GUILD)
                .setAction((ctx, args) -> {
                    if (args.length == 0) {
                        ctx.sendLocalized("options.defaultmutetimeout_set.not_specified", EmoteReference.ERROR);
                        return;
                    }

                    if (!timePattern.matcher(args[0]).matches()) {
                        ctx.sendLocalized("options.defaultmutetimeout_set.wrong_format", EmoteReference.ERROR);
                        return;
                    }

                    var timeoutToSet = Utils.parseTime(args[0]);
                    var time = System.currentTimeMillis() + timeoutToSet;
                    if (time > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)) {
                        ctx.sendLocalized("options.defaultmutetimeout_set.too_long", EmoteReference.ERROR);
                        return;
                    }

                    if (time < 0) {
                        ctx.sendLocalized("options.defaultmutetimeout_set.negative_notice");
                        return;
                    }

                    if (time < 10000) {
                        ctx.sendLocalized("commands.defaultmutetimeout_set.too_short", EmoteReference.ERROR);
                        return;
                    }

                    var dbGuild = ctx.getDBGuild();
                    var guildData = dbGuild.getData();

                    guildData.setSetModTimeout(timeoutToSet);
                    dbGuild.save();

                    ctx.sendLocalized("options.defaultmutetimeout_set.success", EmoteReference.CORRECT, args[0], timeoutToSet);
                }).setShortDescription("Sets the default timeout for the ~>mute command"));


        mute.addOption("defaultmutetimeout:reset", new Option("Default mute timeout reset",
                "Resets the default mute timeout which was set previously with `defaultmusictimeout set`", OptionType.GUILD)
                .setAction((ctx) -> {
                    var dbGuild = ctx.getDBGuild();
                    var guildData = dbGuild.getData();

                    guildData.setSetModTimeout(0L);
                    dbGuild.save();

                    ctx.sendLocalized("options.defaultmutetimeout_reset.success", EmoteReference.CORRECT);
                }).setShortDescription("Resets the default mute timeout."));
    }

    @Subscribe
    public void unmute(CommandRegistry commandRegistry) {
        commandRegistry.register("unmute", new SimpleCommand(CommandCategory.MODERATION) {
            @Override
            protected void call(Context ctx, String content, String[] args) {
                if (!ctx.getMember().hasPermission(Permission.MODERATE_MEMBERS)) {
                    ctx.sendLocalized("commands.unmute.no_permissions", EmoteReference.ERROR);
                    return;
                }

                var dbGuild = ctx.getDBGuild();
                var reason = "Not specified";

                if (args.length > 1) {
                    reason = StringUtils.splitArgs(content, 2)[1];
                }

                var mentionedMembers = ctx.getMentionedMembers();
                if (mentionedMembers.isEmpty()) {
                    ctx.sendLocalized("commands.unmute.no_mentions", EmoteReference.ERROR);
                    return;
                }

                if (!ctx.getSelfMember().hasPermission(Permission.MODERATE_MEMBERS)) {
                    ctx.sendLocalized("commands.mute.no_moderate_members", EmoteReference.ERROR);
                    return;
                }

                final var finalReason = Utils.mentionPattern.matcher(reason).replaceAll("");

                mentionedMembers.forEach(member -> {
                    var user = member.getUser();

                    if (!ctx.getSelfMember().canInteract(member)) {
                        ctx.sendLocalized("commands.mute.self_hierarchy_error", EmoteReference.ERROR);
                        return;
                    }

                    if (!ctx.getMember().canInteract(member)) {
                        ctx.sendLocalized("commands.mute.user_hierarchy_error", EmoteReference.ERROR);
                        return;
                    }

                    if (member.isTimedOut()) {
                        member.removeTimeout()
                                .reason(String.format("Unmuted by %#s: %s", ctx.getAuthor(), finalReason))
                                .queue();

                        ctx.sendLocalized("commands.unmute.success", EmoteReference.CORRECT, user.getName());

                        dbGuild.getData().setCases(dbGuild.getData().getCases() + 1);
                        dbGuild.saveAsync();
                        ModLog.log(ctx.getMember(), user, finalReason, "none", ModLog.ModAction.UNMUTE, dbGuild.getData().getCases());
                    } else {
                        ctx.sendLocalized("commands.unmute.not_muted", EmoteReference.ERROR);
                    }
                });
            }

            @Override
            public HelpContent help() {
                return new HelpContent.Builder()
                        .setDescription("Un-mutes the specified users.")
                        .setUsage("`~>unmute <@user> [reason]`")
                        .addParameter("@user", "The users to un-mute. Needs to be mentions (pings)")
                        .addParameter("reason", "The reason for the un-mute. This is optional.")
                        .build();
            }
        });
    }
}

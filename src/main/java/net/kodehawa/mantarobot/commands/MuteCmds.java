/*
 * Copyright (C) 2016-2018 David Alejandro Rubio Escares / Kodehawa
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

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.kodehawa.mantarobot.commands.moderation.ModLog;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.Category;
import net.kodehawa.mantarobot.core.modules.commands.base.Command;
import net.kodehawa.mantarobot.core.modules.commands.base.CommandPermission;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.ManagedDatabase;
import net.kodehawa.mantarobot.db.entities.DBGuild;
import net.kodehawa.mantarobot.db.entities.MantaroObj;
import net.kodehawa.mantarobot.db.entities.helpers.GuildData;
import net.kodehawa.mantarobot.options.core.Option;
import net.kodehawa.mantarobot.options.core.OptionType;
import net.kodehawa.mantarobot.utils.DiscordUtils;
import net.kodehawa.mantarobot.utils.Pair;
import net.kodehawa.mantarobot.utils.StringUtils;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

@Module
@SuppressWarnings("unused")
public class MuteCmds {
    private static Pattern timePattern = Pattern.compile("-time [(\\d+)((?:h(?:our(?:s)?)?)|(?:m(?:in(?:ute(?:s)?)?)?)|(?:s(?:ec(?:ond(?:s)?)?)?))]+");

    @Subscribe
    public void mute(CommandRegistry registry) {
        Command mute = registry.register("mute", new SimpleCommand(Category.MODERATION) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {

                if(!(event.getMember().hasPermission(Permission.KICK_MEMBERS) || event.getMember().hasPermission(Permission.BAN_MEMBERS))) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.mute.no_permissions"), EmoteReference.ERROR).queue();
                    return;
                }

                ManagedDatabase db = MantaroData.db();
                DBGuild dbGuild = db.getGuild(event.getGuild());
                GuildData guildData = dbGuild.getData();
                String reason = "Not specified";
                Map<String, Optional<String>> opts = br.com.brjdevs.java.utils.texts.StringUtils.parse(args);

                if(guildData.getMutedRole() == null) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.mute.no_mute_role"), EmoteReference.ERROR).queue();
                    return;
                }

                Role mutedRole = event.getGuild().getRoleById(guildData.getMutedRole());

                if(mutedRole == null) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.mute.null_mute_role"), EmoteReference.ERROR).queue();
                    return;
                }

                if(args.length > 1) {
                    reason = StringUtils.splitArgs(content, 2)[1];
                }

                if(event.getMessage().getMentionedUsers().isEmpty()) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.mute.no_mentions"), EmoteReference.ERROR).queue();
                    return;
                }

                if(!event.getGuild().getSelfMember().hasPermission(event.getChannel(), Permission.MANAGE_ROLES)) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.mute.no_manage_roles"), EmoteReference.ERROR).queue();
                    return;
                }

                //Regex from: Fabricio20
                final String finalReason = timePattern.matcher(reason).replaceAll("");

                MantaroObj data = db.getMantaroData();

                event.getMessage().getMentionedUsers().forEach(user -> {
                    Member m = event.getGuild().getMember(user);
                    long time = guildData.getSetModTimeout() > 0 ? System.currentTimeMillis() + guildData.getSetModTimeout() : 0L;

                    if(opts.containsKey("time")) {
                        if(!opts.get("time").isPresent() || opts.get("time").get().isEmpty()) {
                            event.getChannel().sendMessageFormat(languageContext.get("commands.mute.time_not_specified"), EmoteReference.WARNING).queue();
                            return;
                        }

                        time = System.currentTimeMillis() + Utils.parseTime(opts.get("time").get());

                        if(time > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)) {
                            //smh smh smfh god fuck rethinkdb just
                            //dont
                            event.getChannel().sendMessageFormat(languageContext.get("commands.mute.time_too_long"), EmoteReference.ERROR).queue();
                            //yeah why am I doing this
                            //smh
                            return;
                        }

                        if(time < 0) {
                            event.getChannel().sendMessageFormat(languageContext.get("commands.mute.negative_time_notice"), EmoteReference.ERROR).queue();
                            return;
                        }

                        data.getMutes().put(user.getIdLong(), Pair.of(event.getGuild().getId(), time));
                        data.save();
                        dbGuild.save();
                    } else {
                        if(time > 0) {
                            if(time > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)) {
                                event.getChannel().sendMessageFormat(languageContext.get("commands.mute.default_time_too_long"), EmoteReference.ERROR).queue();
                                return;
                            }

                            data.getMutes().put(user.getIdLong(), Pair.of(event.getGuild().getId(), time));
                            data.save();
                            dbGuild.save();
                        } else {
                            event.getChannel().sendMessageFormat(languageContext.get("commands.mute.no_time"), EmoteReference.ERROR).queue();
                            return;
                        }
                    }


                    if(m.getRoles().contains(mutedRole)) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.mute.already_muted"), EmoteReference.WARNING).queue();
                        return;
                    }

                    if(!event.getGuild().getSelfMember().canInteract(m)) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.mute.self_hierarchy_error"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if(!event.getMember().canInteract(m)) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.mute.user_hierarchy_error"), EmoteReference.ERROR).queue();
                        return;
                    }

                    final DBGuild dbg = db.getGuild(event.getGuild());
                    event.getGuild().getController().addSingleRoleToMember(m, mutedRole)
                            .reason(String.format("Muted by %#s for %s: %s", event.getAuthor(), Utils.formatDuration(time - System.currentTimeMillis()), finalReason))
                            .queue();

                    event.getChannel().sendMessageFormat(languageContext.get("commands.mute.success"), EmoteReference.CORRECT, m.getEffectiveName(), Utils.getHumanizedTime(time - System.currentTimeMillis())).queue();

                    dbg.getData().setCases(dbg.getData().getCases() + 1);
                    dbg.saveAsync();
                    ModLog.log(event.getMember(), user, finalReason, ModLog.ModAction.MUTE, dbg.getData().getCases());
                });
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Mute")
                        .setDescription("**Mutes the specified users**")
                        .addField("Usage", "`~>mute <user> <reason> [-time <time>]` - Mutes the specified users.", false)
                        .addField("Parameters", "`users` - The users to mute. Needs to be mentions.\n" +
                                "`[-time <time>]` - The time to mute an user for. For example `~>mute @Natan#1289 wew, nice -time 1m20s` will mute Natan for 1 minute and 20 seconds.", false)
                        .addField("Considerations", "To unmute an user, do `~>unmute`.", false)
                        .addField("Extended usage", "`time` - can be used with the following parameters: " +
                                "d (days), s (second), m (minutes), h (hour). **For example -time 1d1h will mute for one day and one hour.**", false)
                        .build();
            }
        });

        mute.addOption("defaultmutetimeout:set", new Option("Default mute timeout",
                "Sets the default mute timeout for ~>mute.\n" +
                        "This command will set the timeout of ~>mute to a fixed value **unless you specify another time in the command**\n" +
                        "**Example:** `~>opts defaultmutetimeout set 1m20s`\n" +
                        "**Considerations:** Time is in 1m20s or 1h10m3s format, for example.", OptionType.GUILD)
                .setActionLang((event, args, lang) -> {
                    if(args.length == 0) {
                        event.getChannel().sendMessageFormat(lang.get("options.defaultmutetimeout_set.not_specified"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if(!(args[0]).matches("(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?")) {
                        event.getChannel().sendMessageFormat(lang.get("options.defaultmutetimeout_set.wrong_format"), EmoteReference.ERROR).queue();
                        return;
                    }

                    long timeoutToSet = Utils.parseTime(args[0]);

                    long time = System.currentTimeMillis() + timeoutToSet;

                    if(time > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(10)) {
                        event.getChannel().sendMessageFormat(lang.get("options.defaultmutetimeout_set.too_long"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if(time < 0) {
                        event.getChannel().sendMessage(lang.get("options.defaultmutetimeout_set.negative_notice")).queue();
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    guildData.setSetModTimeout(timeoutToSet);
                    dbGuild.save();

                    event.getChannel().sendMessageFormat(lang.get("options.defaultmutetimeout_set.success"), EmoteReference.CORRECT, args[0], timeoutToSet).queue();
                }).setShortDescription("Sets the default timeout for the ~>mute command"));


                mute.addOption("defaultmutetimeout:reset", new Option("Default mute timeout reset",
                        "Resets the default mute timeout which was set previously with `defaultmusictimeout set`", OptionType.GUILD)
                        .setActionLang((event, lang) -> {
                            DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                            GuildData guildData = dbGuild.getData();

                            guildData.setSetModTimeout(0L);
                            dbGuild.save();

                            event.getChannel().sendMessageFormat(lang.get("options.defaultmutetimeout_reset.success"), EmoteReference.CORRECT).queue();
                        }).setShortDescription("Resets the default mute timeout."));

                mute.addOption("muterole:set", new Option("Mute role set",
                        "Sets this guilds mute role to apply on the ~>mute command.\n" +
                                "To use this command you need to specify a role name. *In case the name contains spaces, the name should" +
                                " be wrapped in quotation marks", OptionType.COMMAND)
                        .setActionLang((event, args, lang) -> {
                            if(args.length < 1) {
                                OptsCmd.onHelp(event);
                                return;
                            }

                            String roleName = String.join(" ", args);
                            DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                            GuildData guildData = dbGuild.getData();

                            Consumer<Role> consumer = (role) -> {
                                guildData.setMutedRole(role.getId());
                                dbGuild.saveAsync();
                                event.getChannel().sendMessageFormat(lang.get("options.muterole_set.success"), EmoteReference.OK, roleName).queue();
                            };

                            Role role = Utils.findRoleSelect(event, roleName, consumer);

                            if(role != null) {
                                consumer.accept(role);
                            }
                        }).setShortDescription("Sets this guilds mute role to apply on the ~>mute command"));

                mute.addOption("muterole:unbind", new Option("Mute Role unbind", "Resets the current value set for the mute role", OptionType.GENERAL)
                        .setActionLang((event, lang) -> {
                            DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                            GuildData guildData = dbGuild.getData();
                            guildData.setMutedRole(null);
                            dbGuild.saveAsync();
                            event.getChannel().sendMessageFormat(lang.get("options.muterole_unbind.success"), EmoteReference.OK).queue();
                        }).setShortDescription("Resets the current value set for the mute role."));
    }

    @Subscribe
    public void unmute(CommandRegistry commandRegistry) {
        commandRegistry.register("unmute", new SimpleCommand(Category.MODERATION, CommandPermission.ADMIN) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {

                if(!event.getMember().hasPermission(Permission.KICK_MEMBERS) || !event.getMember().hasPermission(Permission.KICK_MEMBERS)) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.unmute.no_permissions"), EmoteReference.ERROR).queue();
                    return;
                }

                ManagedDatabase db = MantaroData.db();
                DBGuild dbGuild = db.getGuild(event.getGuild());
                GuildData guildData = dbGuild.getData();
                String reason = "Not specified";

                if(guildData.getMutedRole() == null) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.mute.no_mute_role"), EmoteReference.ERROR).queue();
                    return;
                }

                Role mutedRole = event.getGuild().getRoleById(guildData.getMutedRole());

                if(mutedRole == null) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.mute.null_mute_role"), EmoteReference.ERROR).queue();
                    return;
                }

                if(args.length > 1) {
                    reason = StringUtils.splitArgs(content, 2)[1];
                }

                if(event.getMessage().getMentionedUsers().isEmpty()) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.unmute.no_mentions"), EmoteReference.ERROR).queue();
                    return;
                }

                if(!event.getGuild().getSelfMember().hasPermission(event.getChannel(), Permission.MANAGE_ROLES)) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.mute.no_manage_roles"), EmoteReference.ERROR).queue();
                    return;
                }

                final String finalReason = reason;
                final DBGuild dbg = db.getGuild(event.getGuild());

                event.getMessage().getMentionedUsers().forEach(user -> {
                    Member m = event.getGuild().getMember(user);

                    guildData.getMutedTimelyUsers().remove(user.getIdLong());
                    if(!event.getGuild().getSelfMember().canInteract(m)) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.mute.self_hierarchy_error"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if(!event.getMember().canInteract(m)) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.mute.user_hierarchy_error"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if(m.getRoles().contains(mutedRole)) {
                        event.getGuild().getController().removeSingleRoleFromMember(m, mutedRole)
                                .reason(String.format("Unmuted by %#s: %s", event.getAuthor(), finalReason))
                                .queue();

                        event.getChannel().sendMessageFormat(languageContext.get("commands.unmute.success"), EmoteReference.CORRECT, m.getEffectiveName()).queue();
                        dbg.getData().setCases(dbg.getData().getCases() + 1);
                        dbg.saveAsync();
                        ModLog.log(event.getMember(), user, finalReason, ModLog.ModAction.UNMUTE, db.getGuild(event.getGuild()).getData().getCases());
                    } else {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.unmute.no_role_assigned"), EmoteReference.ERROR).queue();
                    }
                });
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Un-mute")
                        .setDescription("**Un-mutes the specified users**")
                        .addField("Usage", "`~>unmute <user> <reason>` - Un-mutes the specified users.", false)
                        .addField("Parameters", "`users` - The users to un-mute. Needs to be mentions.", false)
                        .addField("Considerations", "To mute an user, do `~>mute`.", false)
                        .build();
            }
        });
    }
}

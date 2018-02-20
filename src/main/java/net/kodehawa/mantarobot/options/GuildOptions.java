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

package net.kodehawa.mantarobot.options;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.entities.TextChannel;
import net.kodehawa.mantarobot.commands.OptsCmd;
import net.kodehawa.mantarobot.core.listeners.operations.InteractiveOperations;
import net.kodehawa.mantarobot.core.listeners.operations.core.Operation;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.data.I18n;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.DBGuild;
import net.kodehawa.mantarobot.db.entities.helpers.GuildData;
import net.kodehawa.mantarobot.options.annotations.Option;
import net.kodehawa.mantarobot.options.core.OptionHandler;
import net.kodehawa.mantarobot.options.core.OptionType;
import net.kodehawa.mantarobot.options.event.OptionRegistryEvent;
import net.kodehawa.mantarobot.utils.DiscordUtils;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;

import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import static net.kodehawa.mantarobot.commands.OptsCmd.optsCmd;

@Option
public class GuildOptions extends OptionHandler {

    public GuildOptions() {
        setType(OptionType.GUILD);
    }

    @Subscribe
    public void onRegistry(OptionRegistryEvent e) {
        //region opts language
        //ironically, don't translate this one.
        registerOption("language:set", "Sets the language of this guild", "Sets the language of this guild. Languages use a language code (example en_US or de_DE).\n" +
                "**Example:** `~>opts language set es_CL`", "Sets the language of this guild", ((event, args) -> {
            if(args.length < 1) {
                OptsCmd.onHelp(event);
                return;
            }

            DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
            GuildData guildData = dbGuild.getData();
            String language = args[0];

            if(!I18n.isValidLanguage(language)) {
                event.getChannel().sendMessageFormat("%s`%s` is not a valid language or it's not yet supported by Mantaro.", EmoteReference.ERROR2, language).queue();
                return;
            }

            guildData.setLang(language);
            dbGuild.save();
            event.getChannel().sendMessageFormat("%sSuccessfully set the language of this server to `%s`", EmoteReference.CORRECT, language).queue();
        }));
        //endregion
        //region opts birthday
        registerOption("birthday:enable", "Birthday Monitoring enable",
                "Enables birthday monitoring. You need the channel **name** and the role name (it assigns that role on birthday)\n" +
                        "**Example:** `~>opts birthday enable general Birthday`, `~>opts birthday enable general \"Happy Birthday\"`",
                "Enables birthday monitoring.", (event, args, lang) -> {
                    if (args.length < 2) {
                        OptsCmd.onHelp(event);
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    try {
                        String channel = args[0];
                        String role = args[1];

                        TextChannel channelObj = Utils.findChannel(event, channel);
                        if (channelObj == null)
                            return;

                        String channelId = channelObj.getId();

                        Role roleObj = event.getGuild().getRolesByName(role.replace(channelId, ""), true).get(0);

                        if (roleObj.isPublicRole()) {
                            event.getChannel().sendMessageFormat(lang.get("options.birthday_enable.public_role"), EmoteReference.ERROR).queue();
                            return;
                        }

                        if (guildData.getGuildAutoRole() != null && roleObj.getId().equals(guildData.getGuildAutoRole())) {
                            event.getChannel().sendMessageFormat(lang.get("options.birthday_enable.autorole"), EmoteReference.ERROR).queue();
                            return;
                        }

                        event.getChannel().sendMessageFormat(
                                String.join("\n", lang.get("options.birthday_enable.warning"),
                                        lang.get("options.birthday_enable.warning_1"),
                                        lang.get("options.birthday_enable.warning_2"),
                                        lang.get("options.birthday_enable.warning_3")), EmoteReference.WARNING, roleObj.getName()
                        ).queue();
                        InteractiveOperations.create(event.getChannel(), event.getAuthor().getIdLong(), 45, interactiveEvent -> {
                            String content = interactiveEvent.getMessage().getContentRaw();
                            if (content.equalsIgnoreCase("yes")) {
                                String roleId = roleObj.getId();
                                guildData.setBirthdayChannel(channelId);
                                guildData.setBirthdayRole(roleId);
                                dbGuild.saveAsync();
                                event.getChannel().sendMessageFormat(lang.get("options.birthday_enable.success"), EmoteReference.MEGA,
                                                channelObj.getAsMention(), channelId, role, roleId
                                        ).queue();
                                return Operation.COMPLETED;
                            } else if (content.equalsIgnoreCase("no")) {
                                interactiveEvent.getChannel().sendMessageFormat(lang.get("general.cancelled"), EmoteReference.CORRECT).queue();
                                return Operation.COMPLETED;
                            }

                            return Operation.IGNORED;
                        });

                    } catch (Exception ex) {
                        if (ex instanceof IndexOutOfBoundsException) {
                            event.getChannel().sendMessageFormat(lang.get("options.birthday_enable.error_channel_1") + "\n" + lang.get("options.birthday_enable.error_channel_2"),
                                    EmoteReference.ERROR
                            ).queue();
                            return;
                        }
                        event.getChannel().sendMessage(lang.get("general.invalid_syntax")).queue();
                        OptsCmd.onHelp(event);
                    }
                });

        registerOption("birthday:disable", "Birthday disable", "Disables birthday monitoring.", (event, lang) -> {
            DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
            GuildData guildData = dbGuild.getData();
            guildData.setBirthdayChannel(null);
            guildData.setBirthdayRole(null);
            dbGuild.saveAsync();
            event.getChannel().sendMessageFormat(lang.get("options.birthday_disable.success"), EmoteReference.MEGA).queue();
        });
        //endregion

        //region prefix
        //region set
        registerOption("prefix:set", "Prefix set",
                "Sets the server prefix.\n" +
                        "**Example:** `~>opts prefix set .`",
                "Sets the server prefix.", (event, args, lang) -> {
                    if (args.length < 1) {
                        onHelp(event);
                        return;
                    }
                    String prefix = args[0];

                    if (prefix.length() > 50) {
                        event.getChannel().sendMessageFormat(lang.get("options.prefix_set.too_long"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if (prefix.isEmpty()) {
                        event.getChannel().sendMessageFormat(lang.get("options.prefix_set.empty_prefix"), EmoteReference.ERROR).queue();
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    guildData.setGuildCustomPrefix(prefix);
                    dbGuild.save();
                    event.getChannel().sendMessageFormat(lang.get("options.prefix_set.success"), EmoteReference.MEGA, prefix).queue();
                });//endregion

        //region clear
        registerOption("prefix:clear", "Prefix clear",
                "Clear the server prefix.\n" +
                        "**Example:** `~>opts prefix clear`", (event, lang) -> {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    guildData.setGuildCustomPrefix(null);
                    dbGuild.save();
                    event.getChannel().sendMessageFormat(lang.get("options.prefix_clear.success"), EmoteReference.MEGA).queue();
                });//endregion
        // endregion

        //region autorole
        //region set
        registerOption("autorole:set", "Autorole set",
                "Sets the server autorole. This means every user who joins will get this role. **You need to use the role name, if it contains spaces" +
                        " you need to wrap it in quotation marks**\n" +
                        "**Example:** `~>opts autorole set Member`, `~>opts autorole set \"Magic Role\"`",
                "Sets the server autorole.", (event, args, lang) -> {
                    if (args.length == 0) {
                        onHelp(event);
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    Consumer<Role> consumer = (role) -> {
                        if (!event.getMember().canInteract(role)) {
                            event.getChannel().sendMessageFormat(lang.get("options.autorole_set.hierarchy_conflict"), EmoteReference.ERROR).queue();
                            return;
                        }

                        if(!event.getGuild().getSelfMember().canInteract(role)) {
                            event.getChannel().sendMessageFormat(lang.get("options.autorole_set.self_hierarchy_conflict"), EmoteReference.ERROR).queue();
                            return;
                        }

                        guildData.setGuildAutoRole(role.getId());
                        dbGuild.saveAsync();
                        event.getChannel().sendMessageFormat(lang.get("options.autorole_set.success"), EmoteReference.CORRECT,
                                role.getName(), role.getPosition()
                        ).queue();
                    };

                    Role role = Utils.findRoleSelect(event, String.join(" ", args), consumer);
                    
                    if(role != null) {
                        consumer.accept(role);
                    }
                });//endregion

        //region unbind
        registerOption("autorole:unbind", "Autorole clear",
                "Clear the server autorole.\n" +
                        "**Example:** `~>opts autorole unbind`",
                "Resets the servers autorole.", (event, args, lang) -> {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    guildData.setGuildAutoRole(null);
                    dbGuild.saveAsync();
                    event.getChannel().sendMessageFormat(lang.get("options.autorole_unbind.success"), EmoteReference.OK).queue();
                });//endregion
        //endregion

        //region usermessage
        //region resetchannel
        registerOption("usermessage:resetchannel", "Reset message channel",
                "Clears the join/leave message channel.\n" +
                        "**Example:** `~>opts usermessage resetchannel`",
                "Clears the join/leave message channel.", (event, args, lang) -> {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    guildData.setLogJoinLeaveChannel(null);
                    guildData.setLogLeaveChannel(null);
                    guildData.setLogJoinChannel(null);
                    dbGuild.save();
                    event.getChannel().sendMessageFormat(lang.get("options.usermessage_resetchannel.success"), EmoteReference.CORRECT).queue();
                });//endregion

        //region resetdata
        registerOption("usermessage:resetdata", "Reset join/leave message data",
                "Resets the join/leave message data.\n" +
                        "**Example:** `~>opts usermessage resetdata`",
                "Resets the join/leave message data.", (event, args, lang) -> {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    guildData.setLeaveMessage(null);
                    guildData.setJoinMessage(null);
                    dbGuild.save();
                    event.getChannel().sendMessageFormat(lang.get("options.usermessage_resetdata.success"), EmoteReference.CORRECT).queue();
                });
        //endregion

        //region channel

        registerOption("usermessage:join:channel", "Sets the join message channel", "Sets the join channel, you need the channel **name**\n" +
                "**Example:** `~>opts usermessage join channel join-magic`\n" +
                "You can reset it by doing `~>opts usermessage join resetchannel`", "Sets the join message channel", (event, args, lang) -> {
            if (args.length == 0) {
                onHelp(event);
                return;
            }

            DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
            GuildData guildData = dbGuild.getData();
            String channelName = args[0];
            Consumer<TextChannel> consumer = tc -> {
                guildData.setLogJoinChannel(tc.getId());
                dbGuild.saveAsync();
                event.getChannel().sendMessageFormat(lang.get("options.usermessage_join_channel.success"), EmoteReference.OK, tc.getAsMention()).queue();
            };

            TextChannel channel = Utils.findChannelSelect(event, channelName, consumer);

            if (channel != null) {
                consumer.accept(channel);
            }
        });

        registerOption("usermessage:join:resetchannel", "Resets the join message channel", "Resets the join message channel", (event, lang) -> {
            DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
            GuildData guildData = dbGuild.getData();
            guildData.setLogJoinChannel(null);
            dbGuild.saveAsync();
            event.getChannel().sendMessageFormat(lang.get("options.usermessage_join_resetchannel.success"), EmoteReference.CORRECT).queue();
        });

        registerOption("usermessage:leave:channel", "Sets the leave message channel", "Sets the leave channel, you need the channel **name**\n" +
                "**Example:** `~>opts usermessage leave channel leave-magic`\n" +
                "You can reset it by doing `~>opts usermessage leave resetchannel`", "Sets the leave message channel", (event, args, lang) -> {
            if (args.length == 0) {
                onHelp(event);
                return;
            }

            DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
            GuildData guildData = dbGuild.getData();
            String channelName = args[0];

            Consumer<TextChannel> consumer = tc -> {
                guildData.setLogLeaveChannel(tc.getId());
                dbGuild.saveAsync();
                event.getChannel().sendMessageFormat(lang.get("options.usermessage_leave_channel.success"), EmoteReference.CORRECT, tc.getAsMention()).queue();
            };

            TextChannel channel = Utils.findChannelSelect(event, channelName, consumer);

            if (channel != null) {
                consumer.accept(channel);
            }
        });

        registerOption("usermessage:leave:resetchannel", "Resets the leave message channel", "Resets the leave message channel", (event, lang) -> {
            DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
            GuildData guildData = dbGuild.getData();
            guildData.setLogLeaveChannel(null);
            dbGuild.saveAsync();
            event.getChannel().sendMessageFormat(lang.get("options.usermessage_leave_resetchannel.success"), EmoteReference.CORRECT).queue();
        });

        registerOption("usermessage:channel", "Set message channel",
                "Sets the join/leave message channel. You need the channel **name**\n" +
                        "**Example:** `~>opts usermessage channel join-magic`\n" +
                        "Warning: if you set this, you cannot set individual join/leave channels unless you reset the channel.",
                "Sets the join/leave message channel.", (event, args, lang) -> {
                    if (args.length == 0) {
                        onHelp(event);
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    String channelName = args[0];

                    Consumer<TextChannel> consumer = textChannel -> {
                        guildData.setLogJoinLeaveChannel(textChannel.getId());
                        dbGuild.save();
                        event.getChannel().sendMessageFormat(lang.get("options.usermessage_channel.success"), EmoteReference.OK, textChannel.getAsMention()).queue();
                    };

                    TextChannel channel = Utils.findChannelSelect(event, channelName, consumer);

                    if (channel != null) {
                        consumer.accept(channel);
                    }
                });//endregion

        //region joinmessage
        registerOption("usermessage:joinmessage", "User join message",
                "Sets the join message.\n" +
                        "**Example:** `~>opts usermessage joinmessage Welcome $(event.user.name) to the $(event.guild.name) server! Hope you have a great time`",
                "Sets the join message.", (event, args, lang) -> {
                    if (args.length == 0) {
                        onHelp(event);
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    String joinMessage = String.join(" ", args);
                    guildData.setJoinMessage(joinMessage);
                    dbGuild.save();
                    event.getChannel().sendMessageFormat(lang.get("options.usermessage_joinmessage.success"), EmoteReference.CORRECT, joinMessage).queue();
                });//endregion

        //region leavemessage
        registerOption("usermessage:leavemessage", "User leave message",
                "Sets the leave message.\n" +
                        "**Example:** `~>opts usermessage leavemessage Sad to see you depart, $(event.user.name)`",
                "Sets the leave message.", (event, args, lang) -> {
                    if (args.length == 0) {
                        onHelp(event);
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    String leaveMessage = String.join(" ", args);
                    guildData.setLeaveMessage(leaveMessage);
                    dbGuild.save();
                    event.getChannel().sendMessageFormat(lang.get("options.usermessage_leavemessage.success"), EmoteReference.CORRECT, leaveMessage).queue();
                });//endregion
        //endregion
        //region autoroles
        //region add
        registerOption("autoroles:add", "Autoroles add",
                "Adds a role to the `~>iam` list.\n" +
                        "You need the name of the iam and the name of the role. If the role contains spaces wrap it in quotation marks.\n" +
                        "**Example:** `~>opts autoroles add member Member`, `~>opts autoroles add wew \"A role with spaces on its name\"`",
                "Adds an auto-assignable role to the iam lists.", (event, args, lang) -> {
                    if (args.length < 2) {
                        onHelp(event);
                        return;
                    }

                    String roleName = args[1];

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    List<Role> roleList = event.getGuild().getRolesByName(roleName, true);
                    if (roleList.size() == 0) {
                        event.getChannel().sendMessageFormat(lang.get("options.autoroles_add.no_role_found"), EmoteReference.ERROR).queue();
                    } else if (roleList.size() == 1) {
                        Role role = roleList.get(0);

                        if (!event.getMember().canInteract(role)) {
                            event.getChannel().sendMessageFormat(lang.get("options.autoroles_add.hierarchy_conflict"), EmoteReference.ERROR).queue();
                            return;
                        }

                        if(!event.getGuild().getSelfMember().canInteract(role)) {
                            event.getChannel().sendMessageFormat(lang.get("options.autoroles_add.self_hierarchy_conflict"), EmoteReference.ERROR).queue();
                            return;
                        }

                        guildData.getAutoroles().put(args[0], role.getId());
                        dbGuild.saveAsync();
                        event.getChannel().sendMessageFormat(lang.get("options.autoroles_add.success"), EmoteReference.OK, args[0], role.getName()).queue();
                    } else {
                        DiscordUtils.selectList(event, roleList, role -> String.format("%s (ID: %s)  | Position: %s", role.getName(),
                                role.getId(), role.getPosition()), s -> ((SimpleCommand) optsCmd).baseEmbed(event, "Select the Role:")
                                        .setDescription(s).build(),
                                role -> {
                                    if (!event.getMember().canInteract(role)) {
                                        event.getChannel().sendMessageFormat(lang.get("options.autoroles_add.hierarchy_conflict"), EmoteReference.ERROR).queue();
                                        return;
                                    }

                                    if(!event.getGuild().getSelfMember().canInteract(role)) {
                                        event.getChannel().sendMessageFormat(lang.get("options.autoroles_add.self_hierarchy_conflict"), EmoteReference.ERROR).queue();
                                        return;
                                    }

                                    guildData.getAutoroles().put(args[0], role.getId());
                                    dbGuild.saveAsync();
                                    event.getChannel().sendMessageFormat(lang.get("options.autoroles_add.success"), EmoteReference.OK, args[0], role.getName()).queue();
                                });
                    }
                });

        //region remove
        registerOption("autoroles:remove", "Autoroles remove",
                "Removes a role from the `~>iam` list.\n" +
                        "You need the name of the iam.\n" +
                        "**Example:** `~>opts autoroles remove iamname`",
                "Removes an auto-assignable role from iam.", (event, args, lang) -> {
                    if (args.length == 0) {
                        onHelp(event);
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    HashMap<String, String> autoroles = guildData.getAutoroles();
                    if (autoroles.containsKey(args[0])) {
                        autoroles.remove(args[0]);
                        dbGuild.saveAsync();
                        event.getChannel().sendMessageFormat(lang.get("options.autoroles_remove.success"), EmoteReference.OK, args[0]).queue();
                    } else {
                        event.getChannel().sendMessageFormat(lang.get("options.autoroles_remove.not_found"), EmoteReference.ERROR).queue();
                    }
                });//endregion

        //region clear
        registerOption("autoroles:clear", "Autoroles clear",
                "Removes all autoroles.",
                "Removes all autoroles.", (event, args, lang) -> {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    dbGuild.getData().getAutoroles().clear();
                    dbGuild.saveAsync();
                    event.getChannel().sendMessageFormat(lang.get("options.autoroles_clear.success"), EmoteReference.CORRECT).queue();
                }
        ); //endregion

        //region custom
        registerOption("admincustom", "Admin custom commands",
                "Locks custom commands to admin-only.\n" +
                        "Example: `~>opts admincustom true`",
                "Locks custom commands to admin-only.", (event, args, lang) -> {
                    if (args.length == 0) {
                        OptsCmd.onHelp(event);
                        return;
                    }

                    String action = args[0];
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    try {
                        guildData.setCustomAdminLock(Boolean.parseBoolean(action));
                        dbGuild.save();
                        String toSend = String.format("%s%s", EmoteReference.CORRECT, Boolean.parseBoolean(action) ? lang.get("options.admincustom.admin_only") : lang.get("options.admincustom.everyone"));
                        event.getChannel().sendMessage(toSend).queue();
                    } catch (Exception ex) {
                        event.getChannel().sendMessageFormat("%sSilly, that's not a boolean value!", EmoteReference.ERROR).queue();
                    }
                });
        //endregion

        registerOption("timedisplay:set", "Time display set", "Toggles between 12h and 24h time display.\n" +
                "Example: `~>opts timedisplay 24h`", "Toggles between 12h and 24h time display.", (event, args, lang) -> {
            DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
            GuildData guildData = dbGuild.getData();

            if (args.length == 0) {
                event.getChannel().sendMessageFormat(lang.get("options.timedisplay_set.no_mode_specified"), EmoteReference.ERROR).queue();
                return;
            }

            String mode = args[0];

            switch (mode) {
                case "12h":
                    event.getChannel().sendMessageFormat(lang.get("options.timedisplay_set.12h"), EmoteReference.CORRECT).queue();
                    guildData.setTimeDisplay(1);
                    dbGuild.save();
                    break;
                case "24h":
                    event.getChannel().sendMessageFormat(lang.get("options.timedisplay_set.24h"), EmoteReference.CORRECT).queue();
                    guildData.setTimeDisplay(0);
                    dbGuild.save();
                    break;
                default:
                    event.getChannel().sendMessageFormat(lang.get("options.timedisplay_set.invalid"), EmoteReference.ERROR).queue();
                    break;
            }
        });

        registerOption("server:role:disallow", "Role disallow", "Disallows all users with a role from executing commands.\n" +
                        "You need to provide the name of the role to disallow from mantaro.\n" +
                        "Example: `~>opts server role disallow bad`, `~>opts server role disallow \"No commands\"`",
                "Disallows all users with a role from executing commands.", (event, args, lang) -> {
                    if (args.length == 0) {
                        event.getChannel().sendMessageFormat(lang.get("options.server_role_disallow.no_name"), EmoteReference.ERROR).queue();
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    String roleName = String.join(" ", args);

                    Consumer<Role> consumer = (role) -> {
                        guildData.getDisabledRoles().add(role.getId());
                        dbGuild.saveAsync();
                        event.getChannel().sendMessageFormat(lang.get("options.server_role_disallow.success"), EmoteReference.CORRECT, role.getName()).queue();
                    };

                    Role role = Utils.findRoleSelect(event, roleName, consumer);

                    if(role != null) {
                        consumer.accept(role);
                    }
                });

        registerOption("server:role:allow", "Role allow", "Allows all users with a role from executing commands.\n" +
                        "You need to provide the name of the role to allow from mantaro. Has to be already disabled.\n" +
                        "Example: `~>opts server role allow bad`, `~>opts server role allow \"No commands\"`",
                "Allows all users with a role from executing commands (Has to be already disabled)", (event, args, lang) -> {
                    if (args.length == 0) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "You need to specify the name of the role!").queue();
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    String roleName = String.join(" ", args);

                    Consumer<Role> consumer = (role) -> {
                        if (!guildData.getDisabledRoles().contains(role.getId())) {
                            event.getChannel().sendMessageFormat(lang.get("options.server_role_allow.not_disabled"), EmoteReference.ERROR).queue();
                            return;
                        }

                        guildData.getDisabledRoles().remove(role.getId());
                        dbGuild.saveAsync();
                        event.getChannel().sendMessageFormat(lang.get("options.server_role_allow.success"), EmoteReference.CORRECT, role.getName()).queue();
                    };

                    Role role = Utils.findRoleSelect(event, roleName, consumer);

                    if(role != null) {
                        consumer.accept(role);
                    }
                });

        registerOption("server:ignorebots:autoroles:toggle",
                "Bot autorole ignore", "Toggles between ignoring bots on autorole assign and not.", (event, lang) -> {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    boolean ignore = guildData.isIgnoreBotsAutoRole();
                    guildData.setIgnoreBotsAutoRole(!ignore);
                    dbGuild.saveAsync();

                    event.getChannel().sendMessageFormat(lang.get("options.server_ignorebots_autoroles_toggle.success"), EmoteReference.CORRECT, guildData.isIgnoreBotsAutoRole()).queue();
                });

        registerOption("server:ignorebots:joinleave:toggle",
                "Bot join/leave ignore", "Toggles between ignoring bots on join/leave message.", (event, lang) -> {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    boolean ignore = guildData.isIgnoreBotsWelcomeMessage();
                    guildData.setIgnoreBotsWelcomeMessage(!ignore);
                    dbGuild.saveAsync();

                    event.getChannel().sendMessageFormat(lang.get("options.server_ignorebots_joinleave_toggle.success"), EmoteReference.CORRECT, guildData.isIgnoreBotsWelcomeMessage()).queue();
                });

        registerOption("levelupmessages:toggle", "Level-up toggle",
                "Toggles level up messages, remember that after this you have to set thee channel and the message!", (event, lang) -> {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    boolean ignore = guildData.isEnabledLevelUpMessages();
                    guildData.setEnabledLevelUpMessages(!ignore);
                    dbGuild.saveAsync();

                    event.getChannel().sendMessageFormat(lang.get("options.levelupmessage_toggle.success"), EmoteReference.CORRECT, guildData.isEnabledLevelUpMessages()).queue();
                });

        registerOption("levelupmessages:message:set", "Level-up message", "Sets the message to display on level up",
                "Sets the level up message", (event, args, lang) -> {
                    if (args.length == 0) {
                        onHelp(event);
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    String levelUpMessage = String.join(" ", args);
                    guildData.setLevelUpMessage(levelUpMessage);
                    dbGuild.saveAsync();
                    event.getChannel().sendMessageFormat(lang.get("options.levelupmessages_message_set.success"), EmoteReference.CORRECT, levelUpMessage).queue();
                });

        registerOption("levelupmessages:message:clear", "Level-up message clear", "Clears the message to display on level up",
                "Clears the message to display on level up", (event, args, lang) -> {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    guildData.setLevelUpMessage(null);
                    dbGuild.saveAsync();

                    event.getChannel().sendMessageFormat(lang.get("options.levelupmessages_message_clear.success"), EmoteReference.CORRECT).queue();
                });

        registerOption("levelupmessages:channel:set", "Level-up message channel",
                "Sets the channel to display level up messages", "Sets the channel to display level up messages",
                (event, args, lang) -> {
                    if (args.length == 0) {
                        onHelp(event);
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    String channelName = args[0];

                    Consumer<TextChannel> consumer = textChannel -> {
                        guildData.setLevelUpChannel(textChannel.getId());
                        dbGuild.saveAsync();
                        event.getChannel().sendMessageFormat(lang.get("options.levelupmessages_channel_set.success"), EmoteReference.OK, textChannel.getAsMention()).queue();
                    };

                    TextChannel channel = Utils.findChannelSelect(event, channelName, consumer);

                    if (channel != null) {
                        consumer.accept(channel);
                    }
        });
    }

    @Override
    public String description() {
        return "Guild Configuration";
    }
}

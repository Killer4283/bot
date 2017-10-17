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

package net.kodehawa.mantarobot.options.opts;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.core.entities.ISnowflake;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.User;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.DBGuild;
import net.kodehawa.mantarobot.db.entities.helpers.GuildData;
import net.kodehawa.mantarobot.options.OptionType;
import net.kodehawa.mantarobot.options.annotations.Option;
import net.kodehawa.mantarobot.options.event.OptionRegistryEvent;
import net.kodehawa.mantarobot.utils.DiscordUtils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;

import java.util.List;
import java.util.stream.Collectors;

import static net.kodehawa.mantarobot.commands.OptsCmd.optsCmd;

@Option
public class ModerationOptions extends OptionHandler {
    public ModerationOptions() {
        setType(OptionType.MODERATION);
    }

    @Subscribe
    public void onRegistry(OptionRegistryEvent e) {
        registerOption("localblacklist:add", "Local Blacklist add",
                "Adds someone to the local blacklist.\n" +
                        "You need to mention the user. You can mention multiple users.\n" +
                        "**Example:** `~>opts localblacklist add @user1 @user2`",
                "Adds someone to the local blacklist.", (event, args) -> {

                    List<User> mentioned = event.getMessage().getMentionedUsers();

                    if(mentioned.isEmpty()) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "**You need to specify the users to locally blacklist.**").queue();
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    List<String> toBlackList = mentioned.stream().map(ISnowflake::getId).collect(Collectors.toList());
                    String blacklisted = mentioned.stream().map(user -> user.getName() + "#" + user.getDiscriminator()).collect(Collectors.joining(","));

                    guildData.getDisabledUsers().addAll(toBlackList);
                    dbGuild.save();

                    event.getChannel().sendMessage(EmoteReference.CORRECT + "Locally blacklisted users: **" + blacklisted + "**").queue();
                });

        registerOption("localblacklist:remove", "Local Blacklist remove",
                "Removes someone from the local blacklist.\n" +
                        "You need to mention the user. You can mention multiple users.\n" +
                        "**Example:** `~>opts localblacklist remove @user1 @user2`",
                "Removes someone from the local blacklist.", (event, args) -> {
                    List<User> mentioned = event.getMessage().getMentionedUsers();

                    if(mentioned.isEmpty()) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "**You need to specify the users to locally blacklist.**").queue();
                        return;
                    }

                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    List<String> toUnBlackList = mentioned.stream().map(ISnowflake::getId).collect(Collectors.toList());
                    String unBlackListed = mentioned.stream().map(user -> user.getName() + "#" + user.getDiscriminator()).collect(Collectors.joining(","));

                    guildData.getDisabledUsers().removeAll(toUnBlackList);
                    dbGuild.save();

                    event.getChannel().sendMessage(EmoteReference.CORRECT + "Locally unblacklisted users: **" + unBlackListed + "**").queue();
                });

        //region logs
        //region enable
        registerOption("logs:enable", "Enable logs",
                "Enables logs. You need to use the channel name, *not* the mention.\n" +
                        "**Example:** `~>opts logs enable mod-logs`",
                "Enables logs.", (event, args) -> {
                    if(args.length < 1) {
                        onHelp(event);
                        return;
                    }

                    String logChannel = args[0];
                    boolean isId = args[0].matches("^[0-9]*$");
                    String id = isId ? logChannel : event.getGuild().getTextChannelsByName(logChannel, true).get(0).getId();
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    guildData.setGuildLogChannel(id);
                    dbGuild.saveAsync();
                    event.getChannel().sendMessage(String.format(EmoteReference.MEGA + "Message logging has been enabled with " +
                                    "parameters -> ``Channel #%s (%s)``",
                            logChannel, id)).queue();
                });

        registerOption("logs:exclude", "Exclude log channel.",
                "Excludes a channel from logging. You need to use the channel name, *not* the mention.\n" +
                        "**Example:** `~>opts logs exclude staff`",
                "Excludes a channel from logging.", (event, args) -> {
                    if(args.length == 0) {
                        onHelp(event);
                        return;
                    }
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();

                    if(args[0].equals("clearchannels")) {
                        guildData.getLogExcludedChannels().clear();
                        dbGuild.saveAsync();
                        event.getChannel().sendMessage(EmoteReference.OK + "Cleared log exceptions!").queue();
                        return;
                    }

                    if(args[0].equals("remove")) {
                        if(args.length < 2) {
                            event.getChannel().sendMessage(EmoteReference.ERROR + "Incorrect argument length.").queue();
                            return;
                        }
                        String channel = args[1];
                        List<TextChannel> channels = event.getGuild().getTextChannelsByName(channel, true);
                        if(channels.size() == 0) {
                            event.getChannel().sendMessage(EmoteReference.ERROR + "I didn't find a channel with that name!").queue();
                        } else if(channels.size() == 1) {
                            TextChannel ch = channels.get(0);
                            guildData.getLogExcludedChannels().remove(ch.getId());
                            dbGuild.saveAsync();
                            event.getChannel().sendMessage(EmoteReference.OK + "Removed logs exception on channel: " + ch.getAsMention()).queue();
                        } else {
                            DiscordUtils.selectList(event, channels, ch -> String.format("%s (ID: %s)", ch.getName(), ch.getId()),
                                    s -> ((SimpleCommand) optsCmd).baseEmbed(event, "Select the Channel:")
                                            .setDescription(s).build(),
                                    ch -> {
                                        guildData.getLogExcludedChannels().remove(ch.getId());
                                        dbGuild.saveAsync();
                                        event.getChannel().sendMessage(EmoteReference.OK + "Removed logs exception on channel: " + ch.getAsMention()).queue();
                                    });
                        }
                        return;
                    }

                    String channel = args[0];
                    List<TextChannel> channels = event.getGuild().getTextChannelsByName(channel, true);
                    if(channels.size() == 0) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "I didn't find a channel with that name!").queue();
                    } else if(channels.size() == 1) {
                        TextChannel ch = channels.get(0);
                        guildData.getLogExcludedChannels().add(ch.getId());
                        dbGuild.saveAsync();
                        event.getChannel().sendMessage(EmoteReference.OK + "Added logs exception on channel: " + ch.getAsMention()).queue();
                    } else {
                        DiscordUtils.selectList(event, channels, ch -> String.format("%s (ID: %s)", ch.getName(), ch.getId()),
                                s -> ((SimpleCommand) optsCmd).baseEmbed(event, "Select the Channel:")
                                        .setDescription(s).build(),
                                ch -> {
                                    guildData.getLogExcludedChannels().add(ch.getId());
                                    dbGuild.saveAsync();
                                    event.getChannel().sendMessage(EmoteReference.OK + "Added logs exception on channel: " + ch.getAsMention()).queue();
                                });
                    }
                });//endregion

        //region disable
        registerOption("logs:disable", "Disable logs",
                "Disables logs.\n" +
                        "**Example:** `~>opts logs disable`",
                "Disables logs.", (event) -> {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData guildData = dbGuild.getData();
                    guildData.setGuildLogChannel(null);
                    dbGuild.saveAsync();
                    event.getChannel().sendMessage(EmoteReference.MEGA + "Message logging has been disabled.").queue();
                });//endregion
        // endregion
    }

    @Override
    public String description() {
        return null;
    }
}

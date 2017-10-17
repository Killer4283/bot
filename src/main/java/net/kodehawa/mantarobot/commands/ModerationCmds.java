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

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.core.exceptions.PermissionException;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.currency.TextChannelGround;
import net.kodehawa.mantarobot.commands.moderation.ModLog;
import net.kodehawa.mantarobot.commands.moderation.WarnAction;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.Category;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.DBGuild;
import net.kodehawa.mantarobot.db.entities.helpers.GuildData;
import net.kodehawa.mantarobot.utils.StringUtils;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;

@Slf4j(topic = "Moderation")
@Module
public class ModerationCmds {
    @Subscribe
    public void softban(CommandRegistry cr) {
        cr.register("softban", new SimpleCommand(Category.MODERATION) {
            @Override
            protected void call(GuildMessageReceivedEvent event, String content, String[] args) {
                Guild guild = event.getGuild();
                User author = event.getAuthor();
                TextChannel channel = event.getChannel();
                Message receivedMessage = event.getMessage();
                String reason = content;

                if(!guild.getMember(author).hasPermission(Permission.BAN_MEMBERS)) {
                    channel.sendMessage(EmoteReference.ERROR2 + "Cannot softban: You don't have the Ban Members permission.").queue();
                    return;
                }

                if(receivedMessage.getMentionedUsers().isEmpty()) {
                    channel.sendMessage(EmoteReference.ERROR + "You must mention 1 or more users to be soft-banned!").queue();
                    return;
                }

                Member selfMember = guild.getSelfMember();

                if(!selfMember.hasPermission(Permission.BAN_MEMBERS)) {
                    channel.sendMessage(EmoteReference.ERROR2 + "Sorry! I don't have permission to ban members in this server!").queue();
                    return;
                }

                for(User user : event.getMessage().getMentionedUsers()) {
                    reason = reason.replaceAll("(\\s+)?<@!?" + user.getId() + ">(\\s+)?", "");
                }

                if(reason.isEmpty()) {
                    reason = "Not specified";
                }

                final String finalReason = reason;

                receivedMessage.getMentionedUsers().forEach(user -> {
                    if(!event.getGuild().getMember(event.getAuthor()).canInteract(event.getGuild().getMember(user))) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "You cannot softban an user in a higher hierarchy than you")
                                .queue();
                        return;
                    }

                    if(event.getAuthor().getId().equals(user.getId())) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "Why are you trying to soft-ban yourself?").queue();
                        return;
                    }

                    Member member = guild.getMember(user);
                    if(member == null) return;

                    //If one of them is in a higher hierarchy than the bot, cannot ban.
                    if(!selfMember.canInteract(member)) {
                        channel.sendMessage(EmoteReference.ERROR2 + "Cannot softban member: " + member.getEffectiveName() + ", they are " +
                                "higher or the same " + "hierarchy than I am!").queue();
                        return;
                    }
                    final DBGuild db = MantaroData.db().getGuild(event.getGuild());

                    //Proceed to ban them. Again, using queue so I don't get rate limited.
                    guild.getController().ban(member, 7).reason(finalReason).queue(
                            success -> {
                                user.openPrivateChannel().complete().sendMessage(EmoteReference.MEGA + "You were **softbanned** by " + event
                                        .getAuthor().getName() + "#"
                                        + event.getAuthor().getDiscriminator() + " for reason " + finalReason + ".").queue();
                                db.getData().setCases(db.getData().getCases() + 1);
                                db.saveAsync();
                                channel.sendMessage(EmoteReference.ZAP + "You'll be missed... haha just kidding " + member.getEffectiveName())
                                        .queue(); //Quite funny, I think.
                                guild.getController().unban(member.getUser()).queue(aVoid -> {
                                }, error -> {
                                    if(error instanceof PermissionException) {
                                        channel.sendMessage(String.format(EmoteReference.ERROR + "Error unbanning [%s]: (No permission " +
                                                "provided: %s)", member.getEffectiveName(), ((PermissionException) error).getPermission()))
                                                .queue();
                                    } else {
                                        channel.sendMessage(String.format(EmoteReference.ERROR + "Unknown error while unbanning [%s]: <%s>: " +
                                                "%s", member.getEffectiveName(), error.getClass().getSimpleName(), error.getMessage()))
                                                .queue();
                                        log.warn("Unexpected error while unbanning someone.", error);
                                    }
                                });


                                ModLog.log(event.getMember(), user, finalReason, ModLog.ModAction.KICK, db.getData().getCases());
                                TextChannelGround.of(event).dropItemWithChance(2, 2);
                            },
                            error -> {
                                if(error instanceof PermissionException) {
                                    channel.sendMessage(String.format(EmoteReference.ERROR + "Error softbanning [%s]: (No permission " +
                                            "provided: %s)", member.getEffectiveName(), ((PermissionException) error).getPermission()))
                                            .queue();
                                } else {
                                    channel.sendMessage(String.format(EmoteReference.ERROR + "Unknown error while softbanning [%s]: <%s>: " +
                                            "%s", member.getEffectiveName(), error.getClass().getSimpleName(), error.getMessage()))
                                            .queue();
                                    log.warn("Unexpected error while softbanning someone.", error);
                                }
                            });
                });
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Softban")
                        .setDescription("**Softban the mentioned user and clears their messages from the past week. (You need Ban " +
                                "Members)**")
                        .addField("Summarizing", "A softban is a ban & instant unban, normally used to clear " +
                                "the user's messages but **without banning the person permanently**.", false)
                        .build();
            }

        });
    }

    @Subscribe
    public void ban(CommandRegistry cr) {
        cr.register("ban", new SimpleCommand(Category.MODERATION) {
            @Override
            protected void call(GuildMessageReceivedEvent event, String content, String[] args) {
                Guild guild = event.getGuild();
                User author = event.getAuthor();
                TextChannel channel = event.getChannel();
                Message receivedMessage = event.getMessage();
                String reason = content;

                if(!guild.getMember(author).hasPermission(net.dv8tion.jda.core.Permission.BAN_MEMBERS)) {
                    channel.sendMessage(EmoteReference.ERROR + "You can't ban: You need the `Ban Users` permission.").queue();
                    return;
                }

                if(receivedMessage.getMentionedUsers().isEmpty()) {
                    channel.sendMessage(EmoteReference.ERROR + "You need to mention at least one user!").queue();
                    return;
                }

                for(User user : event.getMessage().getMentionedUsers()) {
                    reason = reason.replaceAll("(\\s+)?<@!?" + user.getId() + ">(\\s+)?", "");
                }

                if(reason.isEmpty()) {
                    reason = "Not specified";
                }

                final String finalReason = reason;

                receivedMessage.getMentionedUsers().forEach(user -> {
                    if(!event.getGuild().getMember(event.getAuthor()).canInteract(event.getGuild().getMember(user))) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "You cannot ban an user who's higher than you in the " +
                                "server hierarchy! Nice try " + EmoteReference.SMILE).queue();
                        return;
                    }

                    if(event.getAuthor().getId().equals(user.getId())) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "Why are you trying to ban yourself, silly?").queue();
                        return;
                    }

                    Member member = guild.getMember(user);
                    if(member == null) return;
                    if(!guild.getSelfMember().canInteract(member)) {
                        channel.sendMessage(EmoteReference.ERROR + "I can't ban " + member.getEffectiveName() + "; they're higher in the " +
                                "server hierarchy than me!").queue();
                        return;
                    }

                    if(!guild.getSelfMember().hasPermission(net.dv8tion.jda.core.Permission.BAN_MEMBERS)) {
                        channel.sendMessage(EmoteReference.ERROR + "Sorry! I don't have permission to ban members in this server!").queue();
                        return;
                    }
                    final DBGuild db = MantaroData.db().getGuild(event.getGuild());

                    guild.getController().ban(member, 7).reason(finalReason).queue(
                            success -> {
                                user.openPrivateChannel().complete().sendMessage(EmoteReference.MEGA + "You were **banned** by " + event
                                        .getAuthor().getName() + "#"
                                        + event.getAuthor().getDiscriminator() + ". Reason: " + finalReason + ".").queue();
                                db.getData().setCases(db.getData().getCases() + 1);
                                db.saveAsync();
                                channel.sendMessage(EmoteReference.ZAP + "You'll be missed " + user.getName() + "... or not!")
                                        .queue();
                                ModLog.log(event.getMember(), user, finalReason, ModLog.ModAction.BAN, db.getData().getCases());
                                TextChannelGround.of(event).dropItemWithChance(1, 2);
                            },
                            error ->
                            {
                                if(error instanceof PermissionException) {
                                    channel.sendMessage(EmoteReference.ERROR + "Error banning " + user.getName()
                                            + ": " + "(I need the permission " + ((PermissionException) error).getPermission() + ")")
                                            .queue();
                                } else {
                                    channel.sendMessage(EmoteReference.ERROR + "I encountered an unknown error while banning " + member
                                            .getEffectiveName()
                                            + ": " + "<" + error.getClass().getSimpleName() + ">: " + error.getMessage()).queue();

                                    log.warn("Encountered an unexpected error while trying to ban someone.", error);
                                }
                            });
                });
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Ban")
                        .setDescription("**Bans the mentioned users. (You need Ban Members)**")
                        .addField("Usage", "`~>ban <@user> <reason>` - **Bans the specified user**", false)
                        .build();
            }
        });
    }

    @Subscribe
    public void kick(CommandRegistry cr) {
        cr.register("kick", new SimpleCommand(Category.MODERATION) {
            @Override
            protected void call(GuildMessageReceivedEvent event, String content, String[] args) {
                Guild guild = event.getGuild();
                User author = event.getAuthor();
                TextChannel channel = event.getChannel();
                Message receivedMessage = event.getMessage();
                String reason = content;

                if(!guild.getMember(author).hasPermission(net.dv8tion.jda.core.Permission.KICK_MEMBERS)) {
                    channel.sendMessage(EmoteReference.ERROR2 + "Cannot kick: You have no Kick Members permission.").queue();
                    return;
                }

                if(receivedMessage.getMentionedUsers().isEmpty()) {
                    channel.sendMessage(EmoteReference.ERROR + "You must mention 1 or more users to be kicked!").queue();
                    return;
                }

                Member selfMember = guild.getSelfMember();

                if(!selfMember.hasPermission(net.dv8tion.jda.core.Permission.KICK_MEMBERS)) {
                    channel.sendMessage(EmoteReference.ERROR2 + "Sorry! I don't have permission to kick members in this server!").queue();
                    return;
                }

                for(User user : event.getMessage().getMentionedUsers()) {
                    reason = reason.replaceAll("(\\s+)?<@!?" + user.getId() + ">(\\s+)?", "");
                }

                if(reason.isEmpty()) {
                    reason = "Not specified";
                }

                final String finalReason = reason;

                receivedMessage.getMentionedUsers().forEach(user -> {
                    if(!event.getGuild().getMember(event.getAuthor()).canInteract(event.getGuild().getMember(user))) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "You cannot kick an user in a higher hierarchy than you")
                                .queue();
                        return;
                    }

                    if(event.getAuthor().getId().equals(user.getId())) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "Why are you trying to kick yourself?").queue();
                        return;
                    }

                    Member member = guild.getMember(user);
                    if(member == null) return;

                    //If one of them is in a higher hierarchy than the bot, cannot kick.
                    if(!selfMember.canInteract(member)) {
                        channel.sendMessage(EmoteReference.ERROR2 + "Cannot kick member: " + member.getEffectiveName() + ", they are " +
                                "higher or the same " + "hierarchy than I am!").queue();
                        return;
                    }
                    final DBGuild db = MantaroData.db().getGuild(event.getGuild());

                    //Proceed to kick them. Again, using queue so I don't get rate limited.
                    guild.getController().kick(member).reason(finalReason).queue(
                            success -> {
                                if(!user.isBot()) {
                                    user.openPrivateChannel().complete().sendMessage(EmoteReference.MEGA + "You were **kicked** by " + event
                                            .getAuthor().getName() + "#"
                                            + event.getAuthor().getDiscriminator() + " with reason: " + finalReason + ".").queue();
                                }
                                db.getData().setCases(db.getData().getCases() + 1);
                                db.saveAsync();
                                channel.sendMessage(EmoteReference.ZAP + "You will be missed... or not " + user.getName())
                                        .queue(); //Quite funny, I think.
                                ModLog.log(event.getMember(), user, finalReason, ModLog.ModAction.KICK, db.getData().getCases());
                                TextChannelGround.of(event).dropItemWithChance(2, 2);
                            },
                            error -> {
                                if(error instanceof PermissionException) {
                                    channel.sendMessage(String.format(EmoteReference.ERROR + "Error kicking [%s]: (No permission " +
                                            "provided: %s)", member.getEffectiveName(), ((PermissionException) error).getPermission().getName()))
                                            .queue();
                                } else {
                                    channel.sendMessage(String.format(EmoteReference.ERROR + "Unknown error while kicking [%s]: <%s>: " +
                                            "%s", member.getEffectiveName(), error.getClass().getSimpleName(), error.getMessage()))
                                            .queue();
                                    log.warn("Unexpected error while kicking someone.", error);
                                }
                            });
                });
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Kick")
                        .setDescription("**Kicks the mentioned users. (You need Kick Members)**")
                        .addField("Usage", "`~>kick <@user> <reason> - **Kicks the mentioned user   **", false)
                        .build();
            }
        });
    }

    @Subscribe
    public void tempban(CommandRegistry cr) {
        cr.register("tempban", new SimpleCommand(Category.MODERATION) {
            @Override
            protected void call(GuildMessageReceivedEvent event, String content, String[] args) {
                String reason = content;
                Guild guild = event.getGuild();
                User author = event.getAuthor();
                TextChannel channel = event.getChannel();
                Message receivedMessage = event.getMessage();

                if(!guild.getMember(author).hasPermission(net.dv8tion.jda.core.Permission.BAN_MEMBERS)) {
                    channel.sendMessage(EmoteReference.ERROR + "Cannot ban: You have no Ban Members permission.").queue();
                    return;
                }

                if(event.getMessage().getMentionedUsers().isEmpty()) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "You need to mention an user!").queue();
                    return;
                }

                for(User user : event.getMessage().getMentionedUsers()) {
                    reason = reason.replaceAll("(\\s+)?<@!?" + user.getId() + ">(\\s+)?", "");
                }
                int index = reason.indexOf("time:");
                if(index < 0) {
                    event.getChannel().sendMessage(EmoteReference.ERROR +
                            "You cannot temp ban an user without giving me the time!").queue();
                    return;
                }
                String time = reason.substring(index);
                reason = reason.replace(time, "").trim();
                time = time.replaceAll("time:(\\s+)?", "");
                if(reason.isEmpty()) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "You cannot temp ban someone without a reason.!").queue();
                    return;
                }

                if(time.isEmpty()) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "You cannot temp ban someone without giving me the time!")
                            .queue();
                    return;
                }

                final DBGuild db = MantaroData.db().getGuild(event.getGuild());
                long l = Utils.parseTime(time);
                String finalReason = reason;
                String sTime = StringUtils.parseTime(l);
                receivedMessage.getMentionedUsers().forEach(user ->
                        guild.getController().ban(user, 7).queue(
                                success -> {
                                    user.openPrivateChannel().complete().sendMessage(EmoteReference.MEGA + "You were **temporarily banned** by " + event
                                            .getAuthor().getName() + "#"
                                            + event.getAuthor().getDiscriminator() + " with reason: " + finalReason + ".").queue();
                                    db.getData().setCases(db.getData().getCases() + 1);
                                    db.saveAsync();
                                    channel.sendMessage(EmoteReference.ZAP + "You will be missed... or not " + user.getName())
                                            .queue();
                                    ModLog.log(event.getMember(), user, finalReason, ModLog.ModAction.TEMP_BAN, db.getData().getCases(), sTime);
                                    MantaroBot.getTempBanManager().addTempban(
                                            guild.getId() + ":" + user.getId(), l + System.currentTimeMillis());
                                    TextChannelGround.of(event).dropItemWithChance(1, 2);
                                },
                                error ->
                                {
                                    if(error instanceof PermissionException) {
                                        channel.sendMessage(EmoteReference.ERROR + "Error banning " + user.getName()
                                                + ": " + "(I need the permission " + ((PermissionException) error).getPermission() + ")")
                                                .queue();
                                    } else {
                                        channel.sendMessage(EmoteReference.ERROR + "I encountered an unknown error while banning " + user.getName() + ": " + "<" + error.getClass().getSimpleName() + ">: " + error.getMessage()).queue();

                                        log.warn("Encountered an unexpected error while trying to ban someone.", error);
                                    }
                                })
                );
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Tempban Command")
                        .setDescription("**Temporarily bans an user**")
                        .addField("Usage", "`~>tempban <user> <reason> time:<time>`", false)
                        .addField("Example", "`~>tempban @Kodehawa example time:1d`", false)
                        .addField("Extended usage", "`time` - can be used with the following parameters: " +
                                "d (days), s (second), m (minutes), h (hour). **For example time:1d1h will give a day and an hour.**", false)
                        .build();
            }
        });
    }

    //@Subscribe
    public void warn(CommandRegistry cr) {
        cr.register("warn", new SimpleCommand(Category.MODERATION) {
            @Override
            protected void call(GuildMessageReceivedEvent event, String content, String[] args) {

                if(args.length < 2 || event.getMessage().getMentionedUsers().isEmpty()) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "You need to mention someone to warn and the reason!").queue();
                    return;
                }

                try {
                    DBGuild dbGuild = MantaroData.db().getGuild(event.getGuild());
                    GuildData data = dbGuild.getData();
                    User toWarn = event.getMessage().getMentionedUsers().get(0);
                    String reason = args[1];
                    long count = data.getWarnCount().getOrDefault(toWarn.getId(), 0L) + 1;
                    WarnAction action = data.getWarnActions().get(count);

                    if(action != null) {
                        switch(action) {
                            //TODO finish | Might end up calling the command itself instead of handling it entirely here?
                            case BAN:
                                break;
                            case KICK:
                                break;
                            case MUTE:
                                break;
                        }
                        return;
                    }

                    data.getWarnCount().put(toWarn.getId(), count);
                    ModLog.log(event.getMember(), toWarn, reason, ModLog.ModAction.KICK, count, "0");

                    event.getChannel().sendMessage(String.format("%sWarned %s#%s for: **%s** (Warn #%d)",
                            EmoteReference.CORRECT, toWarn.getName(), toWarn.getDiscriminator(), reason, count)).queue();
                    dbGuild.saveAsync();
                } catch(IndexOutOfBoundsException e) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "Incorrect arguments.").queue();
                }
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Warn command")
                        .build();
            }
        });
    }
}

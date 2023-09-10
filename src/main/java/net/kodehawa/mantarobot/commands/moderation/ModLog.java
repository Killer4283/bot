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

package net.kodehawa.mantarobot.commands.moderation;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.currency.profile.Badge;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.ManagedDatabase;
import net.kodehawa.mantarobot.utils.Utils;

public class ModLog {
    private static final ManagedDatabase db = MantaroData.db();

    public static void log(Member author, User target, String reason,
                           String channel, ModAction action, long caseNumber, int messagesDeleted) {
        var guildDB = db.getGuild(author.getGuild());
        var player = db.getPlayer(author);
        var embedBuilder = new EmbedBuilder();

        embedBuilder.addField("Responsible Moderator", author.getEffectiveName(), true);

        if (target != null) {
            embedBuilder.addField("Member", target.getName(), true);
            embedBuilder.setThumbnail(target.getEffectiveAvatarUrl());
        } else {
            embedBuilder.setThumbnail(author.getUser().getEffectiveAvatarUrl());
        }

        embedBuilder.addField("Reason", reason, false);
        embedBuilder.addField("Channel", channel, true);

        if (action == ModAction.PRUNE) {
            embedBuilder.addField("Messages Deleted", String.valueOf(messagesDeleted), true);
        }

        embedBuilder.setAuthor(String.format("%s | Case #%s", Utils.capitalize(action.name()), caseNumber),
                null, author.getUser().getEffectiveAvatarUrl());

        if (!player.hasBadge(Badge.POWER_USER)) {
            player.addBadgeIfAbsent(Badge.POWER_USER);
            player.updateAllChanged();
        }

        if (guildDB.getGuildLogChannel() != null) {
            var logChannel = MantaroBot.getInstance()
                    .getShardManager()
                    .getTextChannelById(guildDB.getGuildLogChannel());

            if (logChannel != null) {
                logChannel.sendMessageEmbeds(embedBuilder.build()).queue();
            }
        }
    }

    //Overload
    public static void log(Member author, User target, String reason,
                           String channel, ModAction action, long caseNumber) {
        log(author, target, reason, channel, action, caseNumber, 0);
    }


    public enum ModAction {
        @SuppressWarnings("unused")
        TEMP_BAN,
        @SuppressWarnings("unused")
        BAN,
        @SuppressWarnings("unused")
        KICK,
        MUTE,
        UNMUTE,
        @SuppressWarnings("unused")
        WARN,
        PRUNE
    }
}


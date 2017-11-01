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

package net.kodehawa.mantarobot.commands.moderation;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.entities.Guild;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.DBGuild;
import net.kodehawa.mantarobot.db.entities.MantaroObj;
import net.kodehawa.mantarobot.db.entities.helpers.GuildData;
import org.apache.commons.lang3.tuple.Pair;

import java.util.Map;

@Slf4j
public class MuteTask {

    public void handle() {
        try {
            MantaroObj data = MantaroData.db().getMantaroData();
            Map<Long, Pair<String, Long>> mutes = data.getMutes();
            log.debug("Checking mutes... data size {}", mutes.size());
            for(Map.Entry<Long, Pair<String, Long>> entry : mutes.entrySet()) {
                try {
                    log.trace("Iteration");
                    Long id = entry.getKey();
                    Pair<String, Long> pair = entry.getValue();
                    String guildId = pair.getKey();
                    long maxTime = pair.getValue();

                    if(MantaroBot.getInstance().getShardForGuild(guildId) == null) {
                        continue;
                    }

                    Guild guild = MantaroBot.getInstance().getGuildById(guildId);
                    DBGuild dbGuild = MantaroData.db().getGuild(guildId);
                    GuildData guildData = dbGuild.getData();

                    if(guild == null) {
                        data.getMutes().remove(id);
                        data.saveAsync();
                        log.debug("Removed {} because guild == null", id);
                        continue;
                    } else if(guild.getMemberById(id) == null) {
                        data.getMutes().remove(id);
                        data.saveAsync();
                        log.debug("Removed {} because member == null", id);
                        continue;
                    }

                    //I spent an entire month trying to figure out why this didn't work to then come to the conclusion that I'm completely stupid.
                    //I was checking against `id` instead of against the mute role id because I probably was high or something when I did this
                    //It literally took me a fucking month to figure this shit out
                    //What in the name of real fuck.
                    //Please hold me.
                    if(guild.getRoleById(guildData.getMutedRole()) == null) {
                        data.getMutes().remove(id);
                        data.saveAsync();
                        log.debug("Removed {} because role == null", id);
                    } else {
                        if(System.currentTimeMillis() > maxTime) {
                            log.debug("Unmuted {} because time ran out", id);
                            data.getMutes().remove(id);
                            data.save();
                            guild.getController().removeRolesFromMember(guild.getMemberById(id), guild.getRoleById(guildData.getMutedRole())).queue();
                            guildData.setCases(guildData.getCases() + 1);
                            dbGuild.saveAsync();
                            ModLog.log(guild.getSelfMember(), MantaroBot.getInstance().getUserById(id), "Mute timeout expired", ModLog.ModAction.UNMUTE, guildData.getCases());
                        }
                    }
                } catch (Exception ignored) { }
            }
        } catch(Exception ignored) { }
    }
}

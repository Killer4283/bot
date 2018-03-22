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

package net.kodehawa.mantarobot.commands.game;

import br.com.brjdevs.java.utils.collections.CollectionUtils;
import com.google.gson.JsonSyntaxException;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.kodehawa.mantarobot.commands.AnimeCmds;
import net.kodehawa.mantarobot.commands.anime.CharacterData;
import net.kodehawa.mantarobot.commands.game.core.GameLobby;
import net.kodehawa.mantarobot.commands.game.core.ImageGame;
import net.kodehawa.mantarobot.commands.info.stats.manager.GameStatsManager;
import net.kodehawa.mantarobot.core.listeners.operations.InteractiveOperations;
import net.kodehawa.mantarobot.core.listeners.operations.core.InteractiveOperation;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.utils.Anilist;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.data.DataManager;
import net.kodehawa.mantarobot.utils.data.SimpleFileDataManager;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

@Slf4j(topic = "Game [Character]")
public class Character extends ImageGame {
    private static final DataManager<List<String>> NAMES = new SimpleFileDataManager("assets/mantaro/texts/animenames.txt");
    @Getter
    private final int maxAttempts = 10;
    private String characterName;
    private List<String> characterNameL;

    public Character() {
        super(10);
    }

    @Override
    public void call(GameLobby lobby, List<String> players) {
        InteractiveOperations.create(lobby.getChannel(), Long.parseLong(lobby.getPlayers().get(0)), 60, new InteractiveOperation() {
            @Override
            public int run(GuildMessageReceivedEvent e) {
                return callDefault(e, lobby, players, characterNameL, getAttempts(), maxAttempts, 0);
            }

            @Override
            public void onExpire() {
                if(lobby.getChannel() == null)
                    return;

                lobby.getChannel().sendMessageFormat(lobby.getLanguageContext().get("commands.game.lobby_timed_out"), EmoteReference.ERROR, String.join(" ,", characterNameL)).queue();
                GameLobby.LOBBYS.remove(lobby.getChannel());
            }

            @Override
            public void onCancel() {
                GameLobby.LOBBYS.remove(lobby.getChannel());
            }
        });
    }

    @Override
    public boolean onStart(GameLobby lobby) {
        final I18nContext languageContext = lobby.getLanguageContext();
        try {
            GameStatsManager.log(name());

            characterNameL = new ArrayList<>();
            characterName = CollectionUtils.random(NAMES.get());
            String imageUrl = Anilist.searchCharacters(characterName).get(0).image().large();

            //Allow for replying with only the first name of the character.
            if(characterName.contains(" ") && !characterName.contains("Sailor")) {
                characterNameL.add(characterName.split(" ")[0]);
            }

            characterNameL.add(characterName);
            sendEmbedImage(lobby.getChannel(), imageUrl, eb -> eb
                    .setTitle(languageContext.get("commands.game.character_start"), null)
                    .setFooter(languageContext.get("commands.game.end_footer"), null)
            ).queue();
            return true;
        } catch (JsonSyntaxException ex) {
            lobby.getChannel().sendMessageFormat(languageContext.get("commands.game.character_load_error"), EmoteReference.WARNING, characterName).queue();
            return false;
        } catch(Exception e) {
            lobby.getChannel().sendMessageFormat(languageContext.get("commands.game.error"), EmoteReference.ERROR).queue();
            log.warn("Exception while setting up a game", e);
            return false;
        }
    }

    @Override
    public String name() {
        return "character";
    }
}

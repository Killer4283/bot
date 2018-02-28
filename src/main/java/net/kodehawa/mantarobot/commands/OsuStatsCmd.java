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

import br.com.brjdevs.java.utils.texts.StringUtils;
import com.google.common.eventbus.Subscribe;
import com.osu.api.ciyfhx.*;
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.osu.OsuMod;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleTreeCommand;
import net.kodehawa.mantarobot.core.modules.commands.SubCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.Category;
import net.kodehawa.mantarobot.core.modules.commands.base.ITreeCommand;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.SentryHelper;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import org.json.JSONException;

import java.awt.*;
import java.text.DecimalFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;

@Slf4j
@Module
@SuppressWarnings("unused")
public class OsuStatsCmd {
    private final Map<String, Object> map = new HashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private String mods1 = "";
    private OsuClient osuClient = new OsuClient(MantaroData.config().get().osuApiKey);

    @Subscribe
    public void osustats(CommandRegistry cr) {
        ITreeCommand osuCommand = (SimpleTreeCommand) cr.register("osustats", new SimpleTreeCommand(Category.GAMES) {
            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "osu! command")
                        .setDescription("**Retrieves information from osu! (Players and scores)**.")
                        .addField("Usage", "`~>osu best <player>` - **Retrieves best scores of the user specified in the specified game mode**.\n"
                                        + "`~>osu recent <player>` - **Retrieves recent scores of the user specified in the specified game mode.**\n"
                                        + "`~>osu user <player>` - **Retrieves information about a osu! player**.\n"
                                , false)
                        .addField("Parameters", "`player` - **The osu! player to look info for.**", false)
                        .addField("Considerations", "You can specify the mode by using `-mode` at the end. For example -mode 3 will look up for mania scores.9\n" +
                                "0: standard, 1: taiko, 2: ctb, 3: mania", false)
                        .addField("Example", "`~>osustats best snoverpk -mode 3` (mania scores)", false)
                        .build();
            }
        });

        osuCommand.addSubCommand("best", new SubCommand() {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content) {
                event.getChannel().sendMessageFormat(languageContext.get("commands.osustats.retrieving_info"), EmoteReference.STOPWATCH).queue(sentMessage -> {
                    Future<String> task = pool.submit(() -> best(content, languageContext));
                    try {
                        sentMessage.editMessage(task.get(16, TimeUnit.SECONDS)).queue();
                    } catch(Exception e) {
                        if(e instanceof TimeoutException) {
                            task.cancel(true);
                            sentMessage.editMessage(String.format(languageContext.get("commands.osustats.timeout"), EmoteReference.ERROR)).queue();
                        } else {
                            SentryHelper.captureException("Error retrieving results from osu!API", e, OsuStatsCmd.class);
                        }
                    }
                });
            }
        });

        osuCommand.addSubCommand("recent", new SubCommand() {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content) {
                event.getChannel().sendMessageFormat(languageContext.get("commands.osustats.retrieving_info"), EmoteReference.STOPWATCH).queue(sentMessage -> {
                    Future<String> task = pool.submit(() -> recent(content, languageContext));
                    try {
                        sentMessage.editMessage(task.get(16, TimeUnit.SECONDS)).queue();
                    } catch(Exception e) {
                        if(e instanceof TimeoutException) {
                            task.cancel(true);
                            sentMessage.editMessage(String.format(languageContext.get("commands.osustats.timeout"), EmoteReference.ERROR)).queue();
                        } else log.warn("Exception thrown while fetching data", e);
                    }
                });
            }
        });

        osuCommand.addSubCommand("user", new SubCommand() {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content) {
                event.getChannel().sendMessage(user(content, languageContext)).queue();
            }
        });

        cr.registerAlias("osustats", "osu");
    }

    private String best(String content, I18nContext languageContext) {
        String finalResponse;
        try {
            long start = System.currentTimeMillis();
            String[] args = content.split(" ");
            Map<String, Optional<String>> options = StringUtils.parse(args);

            int mode = 0;
            boolean modeSpecified = false;
            if(options.containsKey("mode") && options.get("mode").isPresent()) {
                try {
                    mode = Integer.parseInt(options.get("mode").get());
                    modeSpecified = true;
                } catch (NumberFormatException e) {
                    return String.format(languageContext.get("general.invalid_number"), EmoteReference.ERROR);
                }
            }

            String lookup = Utils.replaceArguments(options, String.join(" ", args), "mode");

            if(modeSpecified)
                lookup = lookup.replace(" " + mode, "");

            User osuUser = osuClient.getUser(lookup, map);
            if(osuUser == null) {
                try {
                    osuUser = osuClient.getUser(Long.parseLong(lookup), map);
                } catch (NumberFormatException e) {
                    return String.format(languageContext.get("general.search_no_result"), EmoteReference.ERROR);
                }

                if(osuUser == null) {
                    return String.format(languageContext.get("general.search_no_result"), EmoteReference.ERROR);
                }
            }

            MantaroBot.getInstance().getStatsClient().gauge("osu_user_ping", System.currentTimeMillis() - start);

            map.put("m", mode);
            List<UserScore> userBest = osuClient.getUserBest(osuUser, map);
            MantaroBot.getInstance().getStatsClient().gauge("osu_score_ping", System.currentTimeMillis() - start);
            StringBuilder sb = new StringBuilder();
            StringBuilder modsBuilder = new StringBuilder();

            for(UserScore userScore : userBest) {
                if(userScore.getEnabledMods().size() > 0) {
                    for(Mod mod : userScore.getEnabledMods()) {
                        modsBuilder.append(OsuMod.get(mod).getAbbreviation());
                    }

                    mods1 = modsBuilder.toString();
                    modsBuilder = new StringBuilder();
                }

                BeatMap map = userScore.getBeatMap();
                sb.append(String.format(languageContext.get("commands.osustats.best_format"),
                        map.getTitle().replace("'", ""), map.getVersion(), (mods1.isEmpty() ? "No mod" : mods1), map.getDifficultyRating(),
                        (int) userScore.getPP(), userScore.getRank(), userScore.getMaxCombo()))
                        .append("\n");

                mods1 = "";
            }

            finalResponse = String.format(languageContext.get("commands.osustats.best"), osuUser.getUsername(), mode, sb.toString());
        } catch (JSONException jx) {
            finalResponse = String.format(languageContext.get("general.search_no_result"), EmoteReference.ERROR);
        } catch(Exception e) {
            finalResponse = String.format(languageContext.get("commands.osustats.error"), EmoteReference.ERROR);
        }

        return finalResponse;
    }

    private String recent(String content, I18nContext languageContext) {
        String finalMessage;
        try {
            String[] args = content.split(" ");
            Map<String, Optional<String>> options = StringUtils.parse(args);

            int mode = 0;
            boolean modeSpecified = false;
            if(options.containsKey("mode") && options.get("mode").isPresent()) {
                try {
                    mode = Integer.parseInt(options.get("mode").get());
                    modeSpecified = true;
                } catch (NumberFormatException e) {
                    return String.format(languageContext.get("general.invalid_number"), EmoteReference.ERROR);
                }
            }

            String lookup = Utils.replaceArguments(options, String.join(" ", args), "mode");

            if(modeSpecified)
                lookup = lookup.replace(" " + mode, "");

            User osuUser = osuClient.getUser(lookup, map);
            if(osuUser == null) {
                try {
                    osuUser = osuClient.getUser(Long.parseLong(lookup), map);
                } catch (NumberFormatException e) {
                    return String.format(languageContext.get("general.search_no_result"), EmoteReference.ERROR);
                }

                if(osuUser == null) {
                    return String.format(languageContext.get("general.search_no_result"), EmoteReference.ERROR);
                }
            }

            map.put("m", mode);
            List<UserScore> userRecent = osuClient.getUserRecent(osuUser, map);
            StringBuilder sb = new StringBuilder();
            List<String> recent = new CopyOnWriteArrayList<>();
            int n1 = 0;
            DecimalFormat df = new DecimalFormat("####0.0");
            for(UserScore u : userRecent) {
                if(n1 > 9)
                    break;
                n1++;
                if(u.getEnabledMods().size() > 0) {
                    List<Mod> mods = u.getEnabledMods();
                    StringBuilder sb1 = new StringBuilder();
                    mods.forEach(mod -> sb1.append(OsuMod.get(mod).getAbbreviation()));
                    mods1 = " Mods: " + sb1.toString();
                }

                recent.add(
                        String.format(languageContext.get("commands.osustats.recent_format"), u.getBeatMap().getTitle().replace("'", ""), mods1,
                                df.format(u.getBeatMap().getDifficultyRating()), u.getBeatMap().getCreator(), u.getDate(), u.getMaxCombo()));

                mods1 = "";
            }

            recent.forEach(sb::append);
            finalMessage = String.format(languageContext.get("commands.osustats.recent"), osuUser.getUsername(), mode, sb.toString());

        } catch (JSONException jx) {
            finalMessage = String.format(languageContext.get("general.search_no_result"), EmoteReference.ERROR);
        } catch(Exception e) {
            finalMessage = String.format(languageContext.get("commands.osustats.error"), EmoteReference.ERROR);
        }

        return finalMessage;
    }

    private MessageEmbed user(String content, I18nContext languageContext) {
        MessageEmbed finalMessage;
        try {
            long start = System.currentTimeMillis();

            User osuClientUser = osuClient.getUser(content, map);
            DecimalFormat dfa = new DecimalFormat("####0.00"); //For accuracy
            DecimalFormat df = new DecimalFormat("####0"); //For everything else
            long end = System.currentTimeMillis() - start;
            EmbedBuilder builder = new EmbedBuilder();
            builder.setAuthor(String.format(languageContext.get("commands.osustats.user.header"), osuClientUser.getUsername()), "https://osu.ppy.sh/" + osuClientUser.getUserID(), "https://a.ppy.sh/" + osuClientUser.getUserID())
                    .setColor(Color.GRAY)
                    .addField(languageContext.get("commands.osustats.user.rank"), "#" + df.format(osuClientUser.getPPRank()), true)
                    .addField(String.format(":flag_%s: %s", osuClientUser.getCountry().toLowerCase(), languageContext.get("commands.osustats.user.country_rank")), "#" + df.format(osuClientUser.getPPCountryRank()), true)
                    .addField("PP", df.format(osuClientUser.getPPRaw()) + "pp", true)
                    .addField(languageContext.get("commands.osustats.user.acc"), dfa.format(osuClientUser.getAccuracy()) + "%", true)
                    .addField(languageContext.get("commands.osustats.user.level"), df.format(osuClientUser.getLevel()), true)
                    .addField(languageContext.get("commands.osustats.user.ranked"), df.format(osuClientUser.getRankedScore()), true)
                    .addField("SS", df.format(osuClientUser.getCountRankSS()), true)
                    .addField("S", df.format(osuClientUser.getCountRankS()), true)
                    .addField("A", df.format(osuClientUser.getCountRankA()), true)
                    .setFooter("Response time: " + end + "ms.", null);
            finalMessage = builder.build();
        } catch(Exception e) {
            EmbedBuilder builder = new EmbedBuilder();
            builder.setTitle("Error.", null)
                    .setColor(Color.RED)
                    .addField(languageContext.get("general.description"), String.format(languageContext.get("commands.osustats.user.error_detailed"), e.getMessage()), false);
            finalMessage = builder.build();
        }

        return finalMessage;
    }

}

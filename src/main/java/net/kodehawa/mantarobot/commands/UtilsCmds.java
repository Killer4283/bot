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
import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Message;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.utils.Reminder;
import net.kodehawa.mantarobot.commands.utils.UrbanData;
import net.kodehawa.mantarobot.commands.utils.WeatherData;
import net.kodehawa.mantarobot.commands.utils.birthday.BirthdayCacher;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.SubCommand;
import net.kodehawa.mantarobot.core.modules.commands.TreeCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.Category;
import net.kodehawa.mantarobot.core.modules.commands.base.Command;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.DBGuild;
import net.kodehawa.mantarobot.db.entities.DBUser;
import net.kodehawa.mantarobot.db.entities.helpers.GuildData;
import net.kodehawa.mantarobot.utils.DiscordUtils;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.data.GsonDataManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.awt.*;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static br.com.brjdevs.java.utils.collections.CollectionUtils.random;

@Slf4j
@Module
@SuppressWarnings("unused")
public class UtilsCmds {
    private static Pattern timePattern = Pattern.compile(" -time [(\\d+)((?:h(?:our(?:s)?)?)|(?:m(?:in(?:ute(?:s)?)?)?)|(?:s(?:ec(?:ond(?:s)?)?)?))]+");

    protected static String dateGMT(Guild guild, String tz) {
        DateFormat format = new SimpleDateFormat("dd-MMM-yyyy HH:mm:ss");
        Date date = new Date();

        DBGuild dbGuild = MantaroData.db().getGuild(guild.getId());
        GuildData guildData = dbGuild.getData();

        if(guildData.getTimeDisplay() == 1) {
            format = new SimpleDateFormat("dd-MMM-yyyy hh:mm:ss a");
        }

        format.setTimeZone(TimeZone.getTimeZone(tz));
        return format.format(date);
    }

    @Subscribe
    public void birthday(CommandRegistry registry) {
        registry.register("birthday", new SimpleCommand(Category.UTILS) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                SimpleDateFormat format1 = new SimpleDateFormat("dd-MM-yyyy");
                DBUser user = MantaroData.db().getUser(event.getAuthor());
                if(content.isEmpty()) {
                    onError(event);
                    return;
                }

                if(content.startsWith("remove")) {
                    user.getData().setBirthday(null);
                    user.save();
                    event.getChannel().sendMessageFormat(languageContext.get("commands.birthday.reset"), EmoteReference.CORRECT)
                            .queue();
                    return;
                }

                if(content.startsWith("month")) {
                    BirthdayCacher cacher = MantaroBot.getInstance().getBirthdayCacher();

                    try {
                        if(cacher != null) {
                            if(cacher.cachedBirthdays.isEmpty()) {
                                event.getChannel().sendMessageFormat(languageContext.get("commands.birthday.no_global_birthdays"), EmoteReference.SAD).queue();
                                return;
                            }

                            List<String> ids = event.getGuild().getMemberCache().stream().map(m -> m.getUser().getId()).collect(Collectors.toList());
                            Map<String, String> guildCurrentBirthdays = new HashMap<>();
                            Calendar calendar = Calendar.getInstance();
                            String calendarMonth = String.valueOf(calendar.get(Calendar.MONTH) + 1);
                            String currentMonth = (calendarMonth.length() == 1 ? 0 : "") + calendarMonth;

                            for(Map.Entry<String, String> birthdays : cacher.cachedBirthdays.entrySet()) {
                                if(ids.contains(birthdays.getKey()) && birthdays.getValue().split("-")[1].equals(currentMonth)) {
                                    guildCurrentBirthdays.put(birthdays.getKey(), birthdays.getValue());
                                }
                            }

                            if(guildCurrentBirthdays.isEmpty()) {
                                event.getChannel().sendMessageFormat(languageContext.get("commands.birthday.no_guild_month_birthdays"), EmoteReference.ERROR).queue();
                                return;
                            }

                            String birthdays = guildCurrentBirthdays.entrySet().stream()
                                    .sorted(Comparator.comparingInt(entry -> Integer.parseInt(entry.getValue().split("-")[0])))
                                    .map((entry) -> String.format("+ %-20s : %s ", event.getGuild().getMemberById(entry.getKey()).getEffectiveName(), entry.getValue()))
                                    .collect(Collectors.joining("\n"));

                            List<String> parts = DiscordUtils.divideString(1000, birthdays);
                            List<String> messages = new LinkedList<>();
                            boolean hasReactionPerms = event.getGuild().getSelfMember().hasPermission(event.getChannel(), Permission.MESSAGE_ADD_REACTION);

                            for(String s1 : parts) {
                                messages.add(String.format(languageContext.get("commands.birthday.header"), event.getGuild().getName(), Utils.capitalize(calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, Locale.ENGLISH))) +
                                        (parts.size() > 1 ? (hasReactionPerms ? languageContext.get("general.arrow_react") :
                                                languageContext.get("general.text_menu")) : "") +
                                        String.format("```diff\n%s```", s1));
                            }

                            if(hasReactionPerms) {
                                DiscordUtils.list(event, 45, false, messages);
                            } else {
                                DiscordUtils.listText(event, 45, false, messages);
                            }
                        } else {
                            event.getChannel().sendMessageFormat(languageContext.get("commands.birthday.cache_not_running"), EmoteReference.SAD).queue();
                        }
                    } catch(Exception e) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.birthday.error"), EmoteReference.SAD).queue();
                    }

                    return;
                }

                Date bd1;
                try {
                    String bd;
                    bd = content.replace("/", "-");
                    String[] parts = bd.split("-");
                    if(Integer.parseInt(parts[0]) > 31 || Integer.parseInt(parts[1]) > 12 || Integer.parseInt(parts[2]) > 3000) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.birthday.invalid_date"), EmoteReference.ERROR).queue();
                        return;
                    }

                    bd1 = format1.parse(bd);
                } catch(Exception e) {
                    Optional.ofNullable(args[0]).ifPresent((s -> event.getChannel().sendMessageFormat(languageContext.get("commands.birthday.error_date"), "\u274C", args[0]).queue()));
                    return;
                }

                user.getData().setBirthday(format1.format(bd1));
                user.save();
                event.getChannel().sendMessageFormat(languageContext.get("commands.birthday.added_birthdate"), EmoteReference.CORRECT).queue();
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Birthday")
                        .setDescription("**Sets your birthday date.**\n")
                        .addField(
                                "Usage",
                                "~>birthday <date>. Set your birthday date using this. Only useful if the server has " +
                                        "enabled this functionality\n"
                                        + "**Parameter explanation:**\n"
                                        + "date. A date in dd-mm-yyyy format (13-02-1998 for example)", false
                        )
                        .addField("Tip", "To remove your birthday date do ~>birthday remove", false)
                        .setColor(Color.DARK_GRAY)
                        .build();
            }
        });
    }

    @Subscribe
    public void choose(CommandRegistry registry) {
        registry.register("choose", new SimpleCommand(Category.UTILS) {
            @Override
            public void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                if(args.length < 1) {
                    onHelp(event);
                    return;
                }

                event.getChannel().sendMessageFormat(languageContext.get("commands.choose.success"), EmoteReference.EYES, random(args)).queue();
            }

            @Override
            public String[] splitArgs(String content) {
                return StringUtils.efficientSplitArgs(content, -1);
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return baseEmbed(event, "Choose Command")
                        .setDescription("**Choose between 1 or more things\n" +
                                "It accepts all parameters it gives (Also in quotes to account for spaces if used) and chooses a random one.**")
                        .build();
            }
        });
    }

    @Subscribe
    public void dictionary(CommandRegistry registry) {
        registry.register("dictionary", new SimpleCommand(Category.UTILS) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                if(args.length == 0) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.dictionary.no_word"), EmoteReference.ERROR).queue();
                    return;
                }

                String word = content;

                JSONObject main;
                String definition, part_of_speech, headword, example;

                try {
                    main = new JSONObject(Utils.wgetResty("http://api.pearson.com/v2/dictionaries/laes/entries?headword=" + word, event));
                    JSONArray results = main.getJSONArray("results");
                    JSONObject result = results.getJSONObject(0);
                    JSONArray senses = result.getJSONArray("senses");

                    headword = result.getString("headword");

                    if(result.has("part_of_speech")) part_of_speech = result.getString("part_of_speech");
                    else part_of_speech = "Not found.";

                    if(senses.getJSONObject(0).get("definition") instanceof JSONArray)
                        definition = senses.getJSONObject(0).getJSONArray("definition").getString(0);
                    else
                        definition = senses.getJSONObject(0).getString("definition");

                    try {
                        if(senses.getJSONObject(0).getJSONArray("translations").getJSONObject(0).get(
                                "example") instanceof JSONArray) {
                            example = senses.getJSONObject(0)
                                    .getJSONArray("translations")
                                    .getJSONObject(0)
                                    .getJSONArray("example")
                                    .getJSONObject(0)
                                    .getString("text");
                        } else {
                            example = senses.getJSONObject(0)
                                    .getJSONArray("translations")
                                    .getJSONObject(0)
                                    .getJSONObject("example")
                                    .getString("text");
                        }
                    } catch(Exception e) {
                        example = languageContext.get("general.not_found");
                    }

                } catch(Exception e) {
                    event.getChannel().sendMessageFormat(languageContext.get("general.no_results"), EmoteReference.ERROR).queue();
                    return;
                }

                EmbedBuilder eb = new EmbedBuilder();
                eb.setAuthor("Definition for " + word, null, event.getAuthor().getAvatarUrl())
                        .setThumbnail("https://upload.wikimedia.org/wikipedia/commons/thumb/5/5a/Wikt_dynamic_dictionary_logo.svg/1000px-Wikt_dynamic_dictionary_logo.svg.png")
                        .addField(languageContext.get("general.definition"), "**" + definition + "**", false)
                        .addField(languageContext.get("general.example"), "**" + example + "**", false)
                        .setDescription(
                                String.format(languageContext.get("commands.dictionary.description"), part_of_speech, headword));

                event.getChannel().sendMessage(eb.build()).queue();
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Dictionary command")
                        .setDescription("**Looks up a word in the dictionary.**")
                        .addField("Usage", "`~>dictionary <word>` - Searches a word in the dictionary.", false)
                        .addField("Parameters", "`word` - The word to look for", false)
                        .build();
            }
        });
    }

    @Subscribe
    public void remindme(CommandRegistry registry) {
        registry.register("remindme", new SimpleCommand(Category.UTILS) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                if(content.isEmpty()) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.remindme.nothing_specified"), EmoteReference.WARNING).queue();
                    return;
                }

                if(args[0].equals("list") || args[0].equals("ls")) {
                    List<Reminder> reminders = Reminder.CURRENT_REMINDERS.get(event.getAuthor().getId());

                    if(reminders == null || reminders.isEmpty()) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.remindme.no_reminders"), EmoteReference.ERROR).queue();
                        return;
                    }

                    StringBuilder builder = new StringBuilder();
                    AtomicInteger i = new AtomicInteger();
                    for(Reminder r : reminders) {
                        builder.append("**").append(i.incrementAndGet()).append(".-**").append("R: *").append(r.reminder).append("*, Due in: **")
                                .append(Utils.getHumanizedTime(r.time - System.currentTimeMillis())).append("**").append("\n");
                    }

                    Queue<Message> toSend = new MessageBuilder().append(builder.toString()).buildAll(MessageBuilder.SplitPolicy.NEWLINE);
                    toSend.forEach(message -> event.getChannel().sendMessage(message).queue());

                    return;
                }


                if(args[0].equals("cancel")) {
                    try {
                        List<Reminder> reminders = Reminder.CURRENT_REMINDERS.get(event.getAuthor().getId());

                        if(reminders.isEmpty()) {
                            event.getChannel().sendMessageFormat(languageContext.get("commands.remindme.no_reminders"), EmoteReference.ERROR).queue();
                            return;
                        }

                        if(reminders.size() == 1) {
                            reminders.get(0).cancel();
                            event.getChannel().sendMessageFormat(languageContext.get("commands.remindme.cancel.success"), EmoteReference.CORRECT).queue();
                        } else {
                            DiscordUtils.selectList(event, reminders,
                                    (r) -> String.format("%s, Due in: %s", r.reminder, Utils.getHumanizedTime(r.time - System.currentTimeMillis())),
                                    r1 -> new EmbedBuilder().setColor(Color.CYAN).setTitle(languageContext.get("commands.remindme.cancel.select"), null)
                                            .setDescription(r1)
                                            .setFooter(String.format(languageContext.get("general.timeout"), 10), null).build(),
                                    sr -> {
                                        sr.cancel();
                                        event.getChannel().sendMessage(EmoteReference.CORRECT + "Cancelled your reminder").queue();
                                    });
                        }
                    } catch(Exception e) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.remindme.no_reminders"), EmoteReference.ERROR).queue();
                    }

                    return;
                }


                Map<String, Optional<String>> t = StringUtils.parse(args);

                if(!t.containsKey("time")) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.remindme.no_time"), EmoteReference.ERROR).queue();
                    return;
                }

                if(!t.get("time").isPresent()) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.remindme.no_time"), EmoteReference.ERROR).queue();
                    return;
                }

                String toRemind = timePattern.matcher(content).replaceAll("");
                User user = event.getAuthor();
                long time = Utils.parseTime(t.get("time").get());

                if(time < 10000) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.remindme.too_little_time"), EmoteReference.ERROR).queue();
                    return;
                }

                if(System.currentTimeMillis() + time > System.currentTimeMillis() + TimeUnit.DAYS.toMillis(90)) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.remindme.too_long"), EmoteReference.ERROR).queue();
                    return;
                }

                event.getChannel().sendMessage(String.format(languageContext.get("commands.remindme.success"), EmoteReference.CORRECT, toRemind, Utils.getHumanizedTime(time))).queue();

                //TODO save to db
                new Reminder.Builder()
                        .id(user.getId())
                        .reminder(toRemind)
                        .current(System.currentTimeMillis())
                        .time(time + System.currentTimeMillis())
                        .build()
                        .schedule(); //automatic
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Remind me")
                        .setDescription("**Reminds you of something**")
                        .addField("Usage", "`~>remindme do the laundry -time 1h20m`\n" +
                                "`~>remindme cancel` to cancel a reminder." +
                                "\nTime is in this format: 1h20m (1 hour and 20m). You can use h, m and s (hour, minute, second)", false)
                        .build();
            }
        });
    }

    @Subscribe
    public void time(CommandRegistry registry) {
        registry.register("time", new SimpleCommand(Category.UTILS) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                try {
                    content = content.replace("UTC", "GMT").toUpperCase();
                    DBUser user = MantaroData.db().getUser(event.getMember());
                    String timezone = user.getData().getTimezone() != null ? (content.isEmpty() ? user.getData().getTimezone() : content) : content;

                    if(!Utils.isValidTimeZone(timezone)) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.time.invalid_timezone"), EmoteReference.ERROR).queue();
                        return;
                    }

                    event.getChannel().sendMessage(String.format(languageContext.get("commands.time.success"), EmoteReference.MEGA, dateGMT(event.getGuild(), timezone), timezone)).queue();

                } catch(Exception e) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.time.error"), EmoteReference.ERROR).queue();
                }
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Time")
                        .setDescription("**Get the time in a specific timezone**.\n")
                        .addField(
                                "Usage",
                                "`~>time <timezone>` - **Retrieves the time in the specified timezone [Don't write a country!]**.",
                                false
                        )
                        .addField(
                                "Parameters", "`timezone` - **A valid timezone [no countries!] between GMT-12 and GMT+14**",
                                false
                        )
                        .build();
            }
        });
    }

    @Subscribe
    public void urban(CommandRegistry registry) {
        registry.register("urban", new SimpleCommand(Category.UTILS) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                String commandArguments[] = content.split("->");
                EmbedBuilder embed = new EmbedBuilder();

                if(!content.isEmpty()) {
                    String url = null;

                    try {
                        url = "http://api.urbandictionary.com/v0/define?term=" + URLEncoder.encode(commandArguments[0], "UTF-8");
                    } catch(UnsupportedEncodingException ignored) { }

                    String json = Utils.wgetResty(url, event);
                    UrbanData data = GsonDataManager.GSON_PRETTY.fromJson(json, UrbanData.class);

                    //This shouldn't happen, but it fucking happened.
                    if(commandArguments.length < 1) {
                        return;
                    } else if (commandArguments.length > 2) {
                        onHelp(event);
                        return;
                    }

                    if(data == null || data.getList() == null || data.getList().isEmpty()) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + languageContext.get("general.no_results")).queue();
                        return;
                    }

                    if(commandArguments.length > 1) {
                        int definitionNumber = Integer.parseInt(commandArguments[1]) - 1;
                        UrbanData.List urbanData = data.getList().get(definitionNumber);
                        String definition = urbanData.getDefinition();
                        embed.setAuthor(
                                String.format(languageContext.get("commands.urban.header"), commandArguments[0]), urbanData.getPermalink(), null)
                                .setThumbnail("https://everythingfat.files.wordpress.com/2013/01/ud-logo.jpg")
                                .setDescription(languageContext.get("general.definition") + " " + String.valueOf(definitionNumber + 1))
                                .setColor(Color.GREEN)
                                .addField(languageContext.get("general.definition"), definition.length() > 1000 ? definition.substring(0, 1000) + "..." : definition, false)
                                .addField(languageContext.get("general.example"), urbanData.getExample().length() > 1000 ? urbanData.getExample().substring(0, 1000) + "..." : urbanData.getExample(), false)
                                .addField(":thumbsup:", urbanData.thumbs_up, true)
                                .addField(":thumbsdown:", urbanData.thumbs_down, true)
                                .setFooter(languageContext.get("commands.urban.footer"), null);
                        event.getChannel().sendMessage(embed.build()).queue();
                    } else {
                        UrbanData.List urbanData = data.getList().get(0);
                        embed.setAuthor(
                                String.format(languageContext.get("commands.urban.header"), content), data.getList().get(0).getPermalink(), null)
                                .setDescription(languageContext.get("commands.urban.main_def"))
                                .setThumbnail("https://everythingfat.files.wordpress.com/2013/01/ud-logo.jpg")
                                .setColor(Color.GREEN)
                                .addField(languageContext.get("general.definition"), urbanData.getDefinition().length() > 1000 ? urbanData.getDefinition().substring(0, 1000) + "..." : urbanData.getDefinition(), false)
                                .addField(languageContext.get("general.example"), urbanData.getExample().length() > 1000 ? urbanData.getExample().substring(0, 1000) + "..." : urbanData.getExample(), false)
                                .addField(":thumbsup:", urbanData.thumbs_up, true)
                                .addField(":thumbsdown:", urbanData.thumbs_down, true)
                                .setFooter(languageContext.get("commands.urban.footer"), null);
                        event.getChannel().sendMessage(embed.build()).queue();
                    }
                } else {
                    onHelp(event);
                }
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Urban dictionary")
                        .setColor(Color.CYAN)
                        .setDescription("Retrieves definitions from **Urban Dictionary**.")
                        .addField("Usage",
                                "`~>urban <term>-><number>` - **Retrieve a definition based on the given parameters.**", false)
                        .addField("Parameters", "term - **The term you want to look up**\n"
                                + "number - **(OPTIONAL) Parameter defined with the modifier '->' after the term. You don't need to use it.**\n"
                                + "e.g. putting 2 will fetch the second result on Urban Dictionary", false).build();
            }
        });
    }

    @Subscribe
    public void weather(CommandRegistry registry) {
        registry.register("weather", new SimpleCommand(Category.UTILS) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                if(content.isEmpty()) {
                    onError(event);
                    return;
                }

                EmbedBuilder embed = new EmbedBuilder();
                try {
                    long start = System.currentTimeMillis();
                    WeatherData data = GsonDataManager.GSON_PRETTY.fromJson(
                            Utils.wgetResty(
                                    String.format(
                                            "http://api.openweathermap.org/data/2.5/weather?q=%s&appid=%s",
                                            URLEncoder.encode(content, "UTF-8"),
                                            MantaroData.config().get().weatherAppId
                                    ), event
                            ),
                            WeatherData.class
                    );

                    String countryCode = data.getSys().country;
                    String status = data.getWeather().get(0).main;
                    Double temp = data.getMain().getTemp();
                    double pressure = data.getMain().getPressure();
                    int humidity = data.getMain().getHumidity();
                    Double ws = data.getWind().speed;
                    int cloudiness = data.getClouds().all;

                    Double finalTemperatureCelsius = temp - 273.15;
                    Double finalTemperatureFahrenheit = temp * 9 / 5 - 459.67;
                    Double finalWindSpeedMetric = ws * 3.6;
                    Double finalWindSpeedImperial = ws / 0.447046;
                    long end = System.currentTimeMillis() - start;

                    embed.setColor(Color.CYAN)
                            .setTitle(String.format(languageContext.get("commands.weather.header"), ":flag_" + countryCode.toLowerCase() + ":", content), null)
                            .setDescription(status + " (" + cloudiness + "% clouds)")
                            .addField(":thermometer: " + languageContext.get("commands.weather.temperature"), String.format("%d°C | %d°F", finalTemperatureCelsius.intValue(), finalTemperatureFahrenheit.intValue()), true)
                            .addField(":droplet: " + languageContext.get("commands.weather.humidity"), humidity + "%", true)
                            .addField(":wind_blowing_face: " + languageContext.get("commands.weather.wind_speed"), String.format("%dkm/h | %dmph", finalWindSpeedMetric.intValue(), finalWindSpeedImperial.intValue()), true)
                            .addField(":wind_chime: " + languageContext.get("commands.weather.pressure"), pressure + "hPA", true)
                            .setFooter(String.format(languageContext.get("commands.weather.footer"), end), null)
                            .setThumbnail("https://cdn2.iconfinder.com/data/icons/lovely-weather-icons/32/Thermometer-50-512.png");
                    event.getChannel().sendMessage(embed.build()).queue();
                } catch (NullPointerException npe) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.weather.error"), EmoteReference.ERROR).queue();
                } catch (Exception e) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.weather.error"), EmoteReference.ERROR).queue();
                    log.warn("Exception caught while trying to fetch weather data, maybe the API changed something?", e);
                }
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Weather command")
                        .setDescription(
                                "This command retrieves information from OpenWeatherMap. Used to check **forecast information.**")
                        .addField("Usage",
                                "`~>weather <city>,<countrycode>` - **Retrieves the forecast information for the given location.**",false)
                        .addField("Parameters", "`city` - **Your city name, e.g. New York, **\n"
                                        + "`countrycode` - **(OPTIONAL) The abbreviation for your country, for example US (USA) or MX (Mexico).**",false)
                        .addField("Example", "`~>weather New York, US`", false)
                        .build();
            }
        });
    }

    //Won't translate
    @Subscribe
    public void wiki(CommandRegistry registry) {
        registry.register("wiki", new TreeCommand(Category.UTILS) {
            @Override
            public Command defaultTrigger(GuildMessageReceivedEvent event, String mainCommand, String commandName) {
                return new SubCommand() {
                    @Override
                    protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content) {
                        event.getChannel().sendMessage(EmoteReference.OK + "**For Mantaro's documentation please visit:** https://github.com/Mantaro/MantaroBot/wiki/Home").queue();
                    }
                };
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Wiki command")
                        .setDescription("**Shows a bunch of things related to mantaro's wiki.**\n" +
                                "Avaliable subcommands: `opts`, `custom`, `faq`, `commands`, `modifiers`, `tos`, `usermessage`, `premium`, `items`")
                        .build();
            } //addSubCommand meme incoming...
        }.addSubCommand("opts", (event, s) -> event.getChannel().sendMessage(EmoteReference.OK + "**For Mantaro's documentation on `~>opts` and general bot options please visit:** https://github.com/Mantaro/MantaroBot/wiki/Configuration").queue())
        .addSubCommand("custom", (event, s) -> event.getChannel().sendMessage(EmoteReference.OK + "**For Mantaro's documentation on custom commands please visit:** https://github.com/Mantaro/MantaroBot/wiki/Custom-Commands").queue())
        .addSubCommand("modifiers", (event, s) -> event.getChannel().sendMessage(EmoteReference.OK + "**For Mantaro's documentation in custom commands modifiers please visit:** https://github.com/Mantaro/MantaroBot/wiki/Custom-Command-Modifiers").queue())
        .addSubCommand("commands", (event, s) -> event.getChannel().sendMessage(EmoteReference.OK + "**For Mantaro's documentation on commands and usage please visit:** https://github.com/Mantaro/MantaroBot/wiki/Command-reference-and-documentation").queue())
        .addSubCommand("faq", (event, s) -> event.getChannel().sendMessage(EmoteReference.OK + "**For Mantaro's FAQ please visit:** https://github.com/Mantaro/MantaroBot/wiki/FAQ").queue())
        .addSubCommand("badges", (event, s) -> event.getChannel().sendMessage(EmoteReference.OK + "**For Mantaro's badge documentation please visit:** https://github.com/Mantaro/MantaroBot/wiki/Badge-reference-and-documentation").queue())
        .addSubCommand("tos", (event, s) -> event.getChannel().sendMessage(EmoteReference.OK + "**For Mantaro's ToS please visit:** https://github.com/Mantaro/MantaroBot/wiki/Terms-of-Service").queue())
        .addSubCommand("usermessage", (event, s) -> event.getChannel().sendMessage(EmoteReference.OK + "**For Mantaro's Welcome and Leave message tutorial please visit:** https://github.com/Mantaro/MantaroBot/wiki/Welcome-and-Leave-Messages-tutorial").queue())
        .addSubCommand("premium", (event, s) -> event.getChannel().sendMessage(EmoteReference.OK + "**To see what Mantaro's Premium features offer please visit:** https://github.com/Mantaro/MantaroBot/wiki/Premium-Perks").queue())
        .addSubCommand("items", (event, s) -> event.getChannel().sendMessage(EmoteReference.OK + "**For a list of all collectable (non-purchaseable) items please visit:** https://github.com/Mantaro/MantaroBot/wiki/Collectable-Items").queue()));
    }
}

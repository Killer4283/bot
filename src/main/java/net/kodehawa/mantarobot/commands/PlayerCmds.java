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
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.MessageBuilder;
import net.dv8tion.jda.core.entities.*;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.currency.RateLimiter;
import net.kodehawa.mantarobot.commands.currency.item.ItemStack;
import net.kodehawa.mantarobot.commands.currency.item.Items;
import net.kodehawa.mantarobot.commands.currency.profile.Badge;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.Category;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.DBUser;
import net.kodehawa.mantarobot.db.entities.Player;
import net.kodehawa.mantarobot.db.entities.helpers.PlayerData;
import net.kodehawa.mantarobot.db.entities.helpers.UserData;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static net.kodehawa.mantarobot.utils.StringUtils.SPLIT_PATTERN;

@Module
public class PlayerCmds {
    private final OkHttpClient client = new OkHttpClient();

    @Subscribe
    public void rep(CommandRegistry cr) {
        cr.register("rep", new SimpleCommand(Category.CURRENCY) {
            final RateLimiter rateLimiter = new RateLimiter(TimeUnit.HOURS, 12);

            @Override
            public void call(GuildMessageReceivedEvent event, String content, String[] args) {
                long rl = rateLimiter.tryAgainIn(event.getMember());

                if(event.getMessage().getMentionedUsers().isEmpty()) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "You need to mention at least one user.\n" +
                            (rl > 0 ? "**You'll be able to use this command again in " +
                                    Utils.getVerboseTime(rateLimiter.tryAgainIn(event.getMember())) + ".**" :
                                    "You can rep someone now.")).queue();
                    return;
                }

                if(event.getMessage().getMentionedUsers().get(0).isBot()) {
                    event.getChannel().sendMessage(EmoteReference.THINKING + "You cannot rep a bot.\n" +
                            (rl > 0 ? "**You'll be able to use this command again in " +
                                    Utils.getVerboseTime(rateLimiter.tryAgainIn(event.getMember())) + ".**" :
                                    "You can rep someone now.")).queue();
                    return;
                }

                if(event.getMessage().getMentionedUsers().get(0).equals(event.getAuthor())) {
                    event.getChannel().sendMessage(EmoteReference.THINKING + "You cannot rep yourself.\n" +
                            (rl > 0 ? "**You'll be able to use this command again in " +
                                    Utils.getVerboseTime(rateLimiter.tryAgainIn(event.getMember())) + ".**" :
                                    "You can rep someone now.")).queue();
                    return;
                }

                if(!rateLimiter.process(event.getMember())) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + "You can only rep once every 12 hours.\n**You'll be able to use this command again in " +
                            Utils.getVerboseTime(rateLimiter.tryAgainIn(event.getMember())) + ".**").queue();
                    return;
                }
                User mentioned = event.getMessage().getMentionedUsers().get(0);
                Player player = MantaroData.db().getPlayer(event.getGuild().getMember(mentioned));
                player.addReputation(1L);
                player.save();
                event.getChannel().sendMessage(EmoteReference.CORRECT + "Added reputation to **" + mentioned.getName() + "**").queue();
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Reputation command")
                        .setDescription("**Reps an user**")
                        .addField("Usage", "`~>rep <@user>` - **Gives reputation to x user**", false)
                        .addField("Parameters", "`@user` - user to mention", false)
                        .addField("Important", "Only usable every 12 hours.", false)
                        .build();
            }
        });

        cr.registerAlias("rep", "reputation");
    }

    @Subscribe
    public void profile(CommandRegistry cr) {
        cr.register("profile", new SimpleCommand(Category.CURRENCY) {
            @Override
            public void call(GuildMessageReceivedEvent event, String content, String[] args) {
                Player player = MantaroData.db().getPlayer(event.getMember());
                DBUser u1 = MantaroData.db().getUser(event.getMember());
                User author = event.getAuthor();

                if(args.length > 0 && args[0].equals("timezone")) {

                    if(args.length < 2) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "You need to specify the timezone.").queue();
                        return;
                    }

                    try {
                        UtilsCmds.dateGMT(event.getGuild(), args[1]);
                    } catch(Exception e) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "Not a valid timezone.").queue();
                        return;
                    }

                    u1.getData().setTimezone(args[1]);
                    u1.save();
                    event.getChannel().sendMessage(EmoteReference.CORRECT + "Saved timezone, your profile timezone is now: **" + args[1]
                            + "**").queue();
                    return;
                }

                if(args.length > 0 && args[0].equals("description")) {
                    if(args.length == 1) {
                        event.getChannel().sendMessage(EmoteReference.ERROR +
                                "You need to provide an argument! (set or remove)\n" +
                                "for example, ~>profile description set Hi there!").queue();
                        return;
                    }

                    if(args[1].equals("set")) {
                        int MAX_LENGTH = 300;
                        if(MantaroData.db().getUser(author).isPremium()) MAX_LENGTH = 500;
                        String content1 = SPLIT_PATTERN.split(content, 3)[2];

                        if(content1.length() > MAX_LENGTH) {
                            event.getChannel().sendMessage(EmoteReference.ERROR +
                                    "The description is too long! `(Limit of 300 characters for everyone and 500 for premium users)`")
                                    .queue();
                            return;
                        }

                        player.getData().setDescription(content1);
                        event.getChannel().sendMessage(EmoteReference.POPPER + "Set description to: **" + content1 + "**\n" +
                                "Check your shiny new profile with `~>profile`").queue();
                        player.save();
                        return;
                    }

                    if(args[1].equals("clear")) {
                        player.getData().setDescription(null);
                        event.getChannel().sendMessage(EmoteReference.CORRECT + "Successfully cleared description.").queue();
                        player.save();
                        return;
                    }
                }

                UserData user = MantaroData.db().getUser(event.getMember()).getData();
                Member member = event.getMember();

                if(!event.getMessage().getMentionedUsers().isEmpty()) {
                    author = event.getMessage().getMentionedUsers().get(0);
                    member = event.getGuild().getMember(author);

                    if(author.isBot()) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "Bots don't have profiles.").queue();
                        return;
                    }

                    user = MantaroData.db().getUser(author).getData();
                    player = MantaroData.db().getPlayer(member);
                }

                User user1 = MantaroBot.getInstance().getUserById(player.getData().getMarriedWith());
                String marriedSince = player.getData().marryDate();
                String anniversary = player.getData().anniversary();

                if(args.length > 0 && args[0].equals("anniversary")) {
                    if(anniversary == null) {
                        event.getChannel().sendMessage(EmoteReference.ERROR + "I don't see any anniversary here :(. Maybe you were " +
                                "married before this change was implemented, in that case do ~>marry anniversarystart").queue();
                        return;
                    }
                    event.getChannel().sendMessage(String.format("%sYour anniversary with **%s** is on %s. You married on **%s**",
                            EmoteReference.POPPER, user1.getName(), anniversary, marriedSince)).queue();
                    return;
                }

                PlayerData playerData = player.getData();

                if(player.getMoney() > 7526527671L && player.getData().addBadge(Badge.ALTERNATIVE_WORLD))
                    player.saveAsync();
                if(MantaroData.config().get().isOwner(author) && player.getData().addBadge(Badge.DEVELOPER))
                    player.saveAsync();

                List<Badge> badges = playerData.getBadges();
                Collections.sort(badges);
                String displayBadges = badges.stream().map(Badge::getUnicode).collect(Collectors.joining("  "));

                applyBadge(event.getChannel(), badges.isEmpty() ? null : badges.get(0), author, baseEmbed(event, (user1 == null || !player.getInventory().containsItem(Items.RING) ? "" :
                        EmoteReference.RING) + member.getEffectiveName() + "'s Profile", author.getEffectiveAvatarUrl())
                        .setThumbnail(author.getEffectiveAvatarUrl())
                        .setDescription((badges.isEmpty() ? "" : String.format("**%s**\n", badges.get(0)))
                                + (player.getData().getDescription() == null ? "No description set" : player.getData().getDescription()))
                        .addField(EmoteReference.DOLLAR + "Credits", "$ " + player.getMoney(), false)
                        .addField(EmoteReference.ZAP + "Level", player.getLevel() + " (Experience: " + player.getData().getExperience() +
                                ")", true)
                        .addField(EmoteReference.REP + "Reputation", String.valueOf(player.getReputation()), true)
                        .addField(EmoteReference.POUCH + "Inventory", ItemStack.toString(player.getInventory().asList()), false)
                        .addField(EmoteReference.POPPER + "Birthday", user.getBirthday() != null ? user.getBirthday().substring(0, 5) :
                                "Not specified.", true)
                        .addField(EmoteReference.HEART + "Married with", user1 == null ? "Nobody." : user1.getName() + "#" +
                                user1.getDiscriminator(), true)
                        .addField("Badges", displayBadges.isEmpty() ? "No badges (yet!)" : displayBadges, false)
                        .setFooter("User's timezone: " + (user.getTimezone() == null ? "No timezone set." : user.getTimezone()) + " | " +
                                "Requested by " + event.getAuthor().getName(), event.getAuthor().getAvatarUrl()));
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Profile command.")
                        .setDescription("**Retrieves your current user profile.**")
                        .addField("Usage", "To retrieve your profile, `~>profile`\n" +
                                "To change your description do `~>profile description set <description>`\n" +
                                "To clear it, just do `~>profile description clear`\n" +
                                "To set your timezone do `~>profile timezone <timezone>`", false)
                        .build();
            }
        });
    }

    @Subscribe
    public void badges(CommandRegistry cr) {
        cr.register("badges", new SimpleCommand(Category.CURRENCY) {
            @Override
            protected void call(GuildMessageReceivedEvent event, String content, String[] args) {

                User toLookup = event.getAuthor();
                if(!event.getMessage().getMentionedUsers().isEmpty()) {
                    toLookup = event.getMessage().getMentionedUsers().get(0);
                }

                Player player = MantaroData.db().getPlayer(toLookup);
                PlayerData playerData = player.getData();

                List<Badge> badges = playerData.getBadges();
                Collections.sort(badges);
                AtomicInteger counter = new AtomicInteger();

                String toShow = badges.stream().map(
                        badge -> String.format("**%d.-** %s\n*%4s*", counter.incrementAndGet(), badge, badge.description)
                ).collect(Collectors.joining("\n"));

                if(toShow.isEmpty()) toShow = "No badges to show (yet!)";

                applyBadge(event.getChannel(), badges.isEmpty() ? null : badges.get(0), toLookup, new EmbedBuilder()
                        .setAuthor(toLookup.getName() + "'s badges", null, toLookup.getEffectiveAvatarUrl())
                        .setDescription(toShow)
                        .setThumbnail(toLookup.getEffectiveAvatarUrl()));
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Badge list")
                        .setDescription("**Shows your (or another person)'s badges**\n" +
                                "If you want to check out the badges of another person just mention them.")
                        .build();
            }
        });
    }

    private void applyBadge(MessageChannel channel, Badge badge, User author, EmbedBuilder builder) {
        if(badge == null) {
            channel.sendMessage(builder.build()).queue();
            return;
        }
        Message message = new MessageBuilder().setEmbed(builder.setThumbnail("attachment://avatar.png").build()).build();
        byte[] bytes;
        try {
            String url = author.getEffectiveAvatarUrl();
            if(url.endsWith(".gif")) {
                url = url.substring(0, url.length() - 3) + "png";
            }
            Response res = client.newCall(new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "MantaroBot")
                    .build()
            ).execute();
            ResponseBody body = res.body();
            if(body == null) throw new IOException("o shit body is null");
            bytes = body.bytes();
            res.close();
        } catch(IOException e) {
            throw new AssertionError("o shit io error", e);
        }
        channel.sendFile(badge.apply(bytes), "avatar.png", message).queue();
    }
}

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
import net.dv8tion.jda.core.EmbedBuilder;
import net.dv8tion.jda.core.Permission;
import net.dv8tion.jda.core.entities.Member;
import net.dv8tion.jda.core.entities.MessageEmbed;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.kodehawa.mantarobot.commands.currency.item.Item;
import net.kodehawa.mantarobot.commands.currency.item.ItemStack;
import net.kodehawa.mantarobot.commands.currency.item.ItemType;
import net.kodehawa.mantarobot.commands.currency.item.Items;
import net.kodehawa.mantarobot.commands.currency.profile.Badge;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SimpleCommand;
import net.kodehawa.mantarobot.core.modules.commands.SubCommand;
import net.kodehawa.mantarobot.core.modules.commands.TreeCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.Category;
import net.kodehawa.mantarobot.core.modules.commands.base.Command;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.Player;
import net.kodehawa.mantarobot.utils.DiscordUtils;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.commands.RateLimiter;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static net.kodehawa.mantarobot.utils.Utils.handleDefaultRatelimit;

@Module
@SuppressWarnings("unused")
public class CurrencyCmds {
    private final int TRANSFER_LIMIT = Integer.MAX_VALUE / 3; //around 715m

    @Subscribe
    public void inventory(CommandRegistry cr) {
        cr.register("inventory", new SimpleCommand(Category.CURRENCY) {
            @Override
            public void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                Map<String, Optional<String>> t = StringUtils.parse(args);
                content = Utils.replaceArguments(t, content, "brief", "calculate");
                Member member = Utils.findMember(event, event.getMember(), content);

                if(member == null)
                    return;

                Player player = MantaroData.db().getPlayer(member);

                if(t.containsKey("brief")) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.inventory.brief"), member.getEffectiveName(), ItemStack.toString(player.getInventory().asList())).queue();
                    return;
                }

                if(t.containsKey("calculate")) {
                    long all = player.getInventory().asList().stream()
                            .filter(item -> item.getItem().isSellable())
                            .mapToLong(value -> (long) (value.getItem().getValue() * value.getAmount() * 0.9d))
                            .sum();

                    event.getChannel().sendMessageFormat(languageContext.get("commands.inventory.calculate"), EmoteReference.DIAMOND, all).queue();
                    return;
                }

                EmbedBuilder builder = baseEmbed(event, String.format(languageContext.get("commands.inventory.header"), member.getEffectiveName()), member.getUser().getEffectiveAvatarUrl());
                List<ItemStack> list = player.getInventory().asList();
                List<MessageEmbed.Field> fields = new LinkedList<>();
                if(list.isEmpty())
                    builder.setDescription(languageContext.get("general.dust"));
                else {
                    player.getInventory().asList().forEach(stack -> {
                        long buyValue = stack.getItem().isBuyable() ? stack.getItem().getValue() : 0;
                        long sellValue = stack.getItem().isSellable() ? (long) (stack.getItem().getValue() * 0.9) : 0;
                        fields.add(new MessageEmbed.Field(String.format("%s %s x %d", stack.getItem().getEmoji(), stack.getItem().getName(), stack.getAmount()),
                                String.format(languageContext.get("commands.inventory.format"), buyValue, sellValue, stack.getItem().getDesc()), false));
                    });
                }

                List<List<MessageEmbed.Field>> splitFields = DiscordUtils.divideFields(18, fields);
                boolean hasReactionPerms = event.getGuild().getSelfMember().hasPermission(event.getChannel(), Permission.MESSAGE_ADD_REACTION);

                if(hasReactionPerms) {
                    if(builder.getDescriptionBuilder().length() == 0) {
                        builder.setDescription(String.format(languageContext.get("general.buy_sell_paged_react"), splitFields.size(),
                                String.format(languageContext.get("general.buy_sell_paged_reference"), EmoteReference.BUY, EmoteReference.SELL)));
                    }
                    DiscordUtils.list(event, 45, false, builder, splitFields);
                } else {
                    if(builder.getDescriptionBuilder().length() == 0) {
                        builder.setDescription(String.format(languageContext.get("general.buy_sell_paged_react"), splitFields.size(),
                                String.format(languageContext.get("general.buy_sell_paged_reference"), EmoteReference.BUY, EmoteReference.SELL)));
                    }
                    DiscordUtils.listText(event, 45, false, builder, splitFields);
                }
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Inventory command")
                        .setDescription("**Shows your current inventory.**\n" +
                                "You can use `~>inventory -brief` to get a mobile friendly version.\n" +
                                "Use `~>inventory -calculate` to see how much you'd get if you sell every sellable item on your inventory!").build();
            }
        });

        cr.registerAlias("inventory", "inv");
    }

    @Subscribe
    public void market(CommandRegistry cr) {
        final RateLimiter rateLimiter = new RateLimiter(TimeUnit.SECONDS, 8);

        TreeCommand marketCommand = (TreeCommand) cr.register("market", new TreeCommand(Category.CURRENCY) {
            @Override
            public Command defaultTrigger(GuildMessageReceivedEvent event, String mainCommand, String commandName) {
                return new SubCommand() {
                    @Override
                    protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content) {
                        EmbedBuilder embed = baseEmbed(event, languageContext.get("commands.market.header"))
                                .setThumbnail("https://png.icons8.com/metro/540/shopping-cart.png");
                        List<MessageEmbed.Field> fields = new LinkedList<>();
                        Stream.of(Items.ALL).forEach(item -> {
                            if(!item.isHidden()) {
                                String buyValue = item.isBuyable() ? String.format("$%d", item.getValue()) : "N/A";
                                String sellValue = item.isSellable() ? String.format("$%d", (int) Math.floor(item.getValue() * 0.9)) : "N/A";

                                fields.add(new MessageEmbed.Field(String.format("%s %s", item.getEmoji(), item.getName()),
                                        EmoteReference.BUY + buyValue + " " + EmoteReference.SELL + sellValue, true)
                                );
                            }
                        });

                        List<List<MessageEmbed.Field>> splitFields = DiscordUtils.divideFields(8, fields);
                        boolean hasReactionPerms = event.getGuild().getSelfMember().hasPermission(event.getChannel(), Permission.MESSAGE_ADD_REACTION);

                        if(hasReactionPerms) {
                            embed.setDescription(String.format(languageContext.get("general.buy_sell_paged_react"), splitFields.size(),
                                    String.format(languageContext.get("general.buy_sell_paged_reference"), EmoteReference.BUY, EmoteReference.SELL)));
                            DiscordUtils.list(event, 120, false, embed, splitFields);
                        } else {
                            embed.setDescription(String.format(languageContext.get("general.buy_sell_paged_text"), splitFields.size(),
                                    String.format(languageContext.get("general.buy_sell_paged_reference"), EmoteReference.BUY, EmoteReference.SELL)));
                            DiscordUtils.listText(event, 120, false, embed, splitFields);
                        }
                    }
                };
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Mantaro's market")
                        .setDescription("**List current items for buying and selling.**")
                        .addField("Buying and selling", "To buy do ~>market buy <item emoji>. It will subtract the value from your money" +
                                " and give you the item.\n" +
                                "To sell do `~>market sell all` to sell all your items or `~>market sell <item emoji>` to sell the specified item. " +
                                "**You'll get the sell value of the item on coins to spend.**\n" +
                                "You can check the value of a single item using `~>market price <item emoji>`\n" +
                                "You can send an item to the trash using `~>market dump <amount> <item emoji>`\n" +
                                "Use `~>inventory -calculate` to check how much is your inventory worth.", false)
                        .addField("To know", "If you don't have enough money you cannot buy the items.\n" +
                                "Note: Don't use the item id, it's just for aesthetic reasons, the internal IDs are different than the ones shown here!", false)
                        .addField("Information", "To buy and sell multiple items you need to do `~>market <buy/sell> <amount> <item>`",
                                false)
                        .build();
            }
        });

        marketCommand.setPredicate((event) -> {
            if(!handleDefaultRatelimit(rateLimiter, event.getAuthor(), event))
                return false;

            Player player = MantaroData.db().getPlayer(event.getMember());
            if(player.isLocked()) {
                event.getChannel().sendMessage(EmoteReference.ERROR + "You cannot access the market now.").queue();
                return false;
            }

            return true;
        });

        marketCommand.addSubCommand("dump", new SubCommand() {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content) {
                String[] args = content.split(" ");
                String itemName = content;
                int itemNumber = 1;
                boolean isMassive = !itemName.isEmpty() && itemName.split(" ")[0].matches("^[0-9]*$");
                if(isMassive) {
                    try {
                        itemNumber = Math.abs(Integer.valueOf(itemName.split(" ")[0]));
                        itemName = itemName.replace(args[0], "").trim();
                    } catch (NumberFormatException e) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.dump.invalid"), EmoteReference.ERROR).queue();
                        return;
                    } catch (Exception e) {
                        onHelp(event);
                        return;
                    }
                }

                Item item = Items.fromAny(itemName).orElse(null);

                if(item == null) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.market.dump.non_existent"), EmoteReference.ERROR).queue();
                    return;
                }

                Player player = MantaroData.db().getPlayer(event.getAuthor());

                if(!player.getInventory().containsItem(item)) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.market.dump.player_no_item"), EmoteReference.ERROR).queue();
                    return;
                }

                if(player.getInventory().getAmount(item) < itemNumber) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.market.dump.more_items_than_player"), EmoteReference.ERROR).queue();
                    return;
                }

                player.getInventory().process(new ItemStack(item, -itemNumber));
                player.saveAsync();
                event.getChannel().sendMessageFormat(languageContext.get("commands.market.dump.success"),
                        EmoteReference.CORRECT, itemNumber, item.getEmoji(), item.getName()).queue();
            }
        }).createSubCommandAlias("dump", "trash");

        marketCommand.addSubCommand("price", new SubCommand() {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content) {
                String[] args = content.split(" ");
                String itemName = content.replace(args[0] + " ", "");
                Item item = Items.fromAny(itemName).orElse(null);

                if(item == null) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.market.price.non_existent"), EmoteReference.ERROR).queue();
                    return;
                }

                if(!item.isBuyable() && !item.isSellable()) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.market.price.no_price"), EmoteReference.THINKING).queue();
                    return;
                }

                if(!item.isBuyable()) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.market.price.collectible"),
                            EmoteReference.EYES, (int)(item.getValue() * 0.9)).queue();
                    return;
                }

                event.getChannel().sendMessageFormat(languageContext.get("commands.market.price.success"),
                        EmoteReference.MARKET, item.getEmoji(), item.getName(), item.getValue(), (int) (item.getValue() * 0.9)).queue();
            }
        });

        marketCommand.addSubCommand("sell", new SubCommand() {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content) {
                Player player = MantaroData.db().getPlayer(event.getMember());
                String[] args = content.split(" ");
                String itemName = content;
                int itemNumber = 1;
                boolean isMassive = !itemName.isEmpty() && itemName.split(" ")[0].matches("^[0-9]*$");
                if(isMassive) {
                    try {
                        itemNumber = Math.abs(Integer.valueOf(itemName.split(" ")[0]));
                        itemName = itemName.replace(args[0], "").trim();
                    } catch (NumberFormatException e) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.sell.invalid"), EmoteReference.ERROR).queue();
                        return;
                    } catch (Exception e) {
                        onHelp(event);
                        return;
                    }
                }

                try {
                    if(args[0].equals("all")) {
                        long all = player.getInventory().asList().stream()
                                .filter(item -> item.getItem().isSellable())
                                .mapToLong(value -> (long) (value.getItem().getValue() * value.getAmount() * 0.9d))
                                .sum();

                        player.getInventory().clearOnlySellables();
                        player.addMoney(all);

                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.sell.all.success"), EmoteReference.MONEY, all).queue();

                        player.saveAsync();
                        return;
                    }

                    Item toSell = Items.fromAny(itemName).orElse(null);

                    if(toSell == null) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.sell.non_existent"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if(!toSell.isSellable()) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.sell.no_sell_price"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if(player.getInventory().getAmount(toSell) < 1) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.sell.no_item_player"), EmoteReference.STOP).queue();
                        return;
                    }

                    if(player.getInventory().getAmount(toSell) < itemNumber) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.sell.more_items_than_player"), EmoteReference.ERROR).queue();
                        return;
                    }

                    int many = itemNumber * -1;
                    long amount = Math.round((toSell.getValue() * 0.9)) * Math.abs(many);
                    player.getInventory().process(new ItemStack(toSell, many));
                    player.addMoney(amount);
                    player.getData().setMarketUsed(player.getData().getMarketUsed() + 1);
                    event.getChannel().sendMessageFormat(languageContext.get("commands.market.sell.success"),
                            EmoteReference.CORRECT, Math.abs(many), toSell.getName(), amount).queue();

                    player.saveAsync();
                } catch(Exception e) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + languageContext.get("general.invalid_syntax")).queue();
                }
            }
        });

        marketCommand.addSubCommand("buy", new SubCommand() {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content) {
                Player player = MantaroData.db().getPlayer(event.getMember());
                String[] args = content.split(" ");
                String itemName = content;
                int itemNumber = 1;
                boolean isMassive = !itemName.isEmpty() && itemName.split(" ")[0].matches("^[0-9]*$");
                if(isMassive) {
                    try {
                        itemNumber = Math.abs(Integer.valueOf(itemName.split(" ")[0]));
                        itemName = itemName.replace(args[0], "").trim();
                    } catch (Exception e) {
                        if (e instanceof NumberFormatException) {
                            event.getChannel().sendMessageFormat(languageContext.get("commands.market.buy.invalid"), EmoteReference.ERROR).queue();
                        } else {
                            onHelp(event);
                            return;
                        }
                    }
                }

                Item itemToBuy = Items.fromAnyNoId(itemName).orElse(null);

                if(itemToBuy == null) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.market.buy.non_existent"), EmoteReference.ERROR).queue();
                    return;
                }

                try {
                    if(!itemToBuy.isBuyable()) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.buy.no_buy_price"), EmoteReference.ERROR).queue();
                        return;
                    }

                    ItemStack stack = player.getInventory().getStackOf(itemToBuy);
                    if((stack != null && !stack.canJoin(new ItemStack(itemToBuy, itemNumber))) || itemNumber > 5000) {
                        //assume overflow
                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.buy.item_limit_reached"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if(player.removeMoney(itemToBuy.getValue() * itemNumber)) {
                        player.getInventory().process(new ItemStack(itemToBuy, itemNumber));
                        player.getData().addBadgeIfAbsent(Badge.BUYER);
                        player.getData().setMarketUsed(player.getData().getMarketUsed() + 1);
                        player.saveAsync();

                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.buy.success"),
                                EmoteReference.OK, itemNumber, itemToBuy.getEmoji(), itemToBuy.getValue() * itemNumber, player.getMoney()).queue();

                    } else {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.market.buy.not_enough_money"), EmoteReference.STOP).queue();
                    }
                } catch(Exception e) {
                    event.getChannel().sendMessage(EmoteReference.ERROR + languageContext.get("general.invalid_syntax")).queue();
                }
            }
        });
    }

    @Subscribe
    public void transferItems(CommandRegistry cr) {
        cr.register("itemtransfer", new SimpleCommand(Category.CURRENCY) {
            RateLimiter rl = new RateLimiter(TimeUnit.SECONDS, 10);

            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                if(args.length < 2) {
                    onError(event);
                    return;
                }

                List<User> mentionedUsers = event.getMessage().getMentionedUsers();
                if(mentionedUsers.size() == 0) {
                    event.getChannel().sendMessageFormat(languageContext.get("general.mention_user_required"), EmoteReference.ERROR).queue();
                }
                else {
                    User giveTo = mentionedUsers.get(0);

                    if(event.getAuthor().getId().equals(giveTo.getId())) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.transfer_yourself_note"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if(giveTo.isBot()) {
                        event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.bot_notice"), EmoteReference.ERROR).queue();
                        return;
                    }

                    if(!handleDefaultRatelimit(rl, event.getAuthor(), event))
                        return;

                    Item item = Items.fromAny(args[1]).orElse(null);
                    if(item == null) {
                        event.getChannel().sendMessage(languageContext.get("general.item_lookup.no_item_emoji")).queue();
                    } else {
                        Player player = MantaroData.db().getPlayer(event.getAuthor());
                        Player giveToPlayer = MantaroData.db().getPlayer(giveTo);

                        if(player.isLocked()) {
                            event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.locked_notice"), EmoteReference.ERROR).queue();
                            return;
                        }

                        if(args.length == 2) {
                            if(player.getInventory().containsItem(item)) {
                                if(item.isHidden()) {
                                    event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.hidden_item"), EmoteReference.ERROR).queue();
                                    return;
                                }

                                if(giveToPlayer.getInventory().asMap().getOrDefault(item, new ItemStack(item, 0)).getAmount() >= 5000) {
                                    event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.overflow"), EmoteReference.ERROR).queue();
                                    return;
                                }

                                player.getInventory().process(new ItemStack(item, -1));
                                giveToPlayer.getInventory().process(new ItemStack(item, 1));
                                event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.success"), EmoteReference.OK, event.getMember().getEffectiveName(),
                                        item.getName(), event.getGuild().getMember(giveTo).getEffectiveName()).queue();
                            } else {
                                event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.multiple_items_error"), EmoteReference.ERROR).queue();
                            }

                            player.saveAsync();
                            giveToPlayer.saveAsync();
                            return;
                        }

                        try {
                            int amount = Math.abs(Integer.parseInt(args[2]));
                            if(player.getInventory().containsItem(item) && player.getInventory().getAmount(item) >= amount) {
                                if(item.isHidden()) {
                                    event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.hidden_item"), EmoteReference.ERROR).queue();
                                    return;
                                }

                                if(giveToPlayer.getInventory().asMap().getOrDefault(item, new ItemStack(item, 0)).getAmount() + amount > 5000) {
                                    event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.overflow"), EmoteReference.ERROR).queue();
                                    return;
                                }

                                player.getInventory().process(new ItemStack(item, amount * -1));
                                giveToPlayer.getInventory().process(new ItemStack(item, amount));

                                event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.success"), EmoteReference.OK,
                                        event.getMember().getEffectiveName(), amount, item.getName(), event.getGuild().getMember(giveTo).getEffectiveName()).queue();
                            } else {
                                event.getChannel().sendMessageFormat(languageContext.get("commands.itemtransfer.error"), EmoteReference.ERROR).queue();
                            }
                        } catch(NumberFormatException nfe) {
                            event.getChannel().sendMessageFormat(languageContext.get("general.invalid_number"), EmoteReference.ERROR).queue();
                        }

                        player.saveAsync();
                        giveToPlayer.saveAsync();
                    }
                }
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Transfer Items command")
                        .setDescription("**Transfers items from you to another player.**")
                        .addField("Usage", "`~>itemtransfer <@user> <item emoji or part of the name> <amount (optional)>` - **Transfers the item to player x**", false)
                        .addField("Parameters", "`@user` - user to send the item to\n" +
                                "`item emoji` - write out the emoji of the item you want to send, or you can just use part of its name.\n" +
                                "`amount` - optional, send a specific amount of an item to someone.", false)
                        .addField("Important", "You cannot send more items than what you already have", false)
                        .build();
            }
        });

        cr.registerAlias("itemtransfer", "transferitems");
    }

    @Subscribe
    //Should be called return land tbh, what the fuck.
    public void transfer(CommandRegistry cr) {
        cr.register("transfer", new SimpleCommand(Category.CURRENCY) {
            RateLimiter rl = new RateLimiter(TimeUnit.SECONDS, 10);

            @Override
            public void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                if(event.getMessage().getMentionedUsers().isEmpty()) {
                    event.getChannel().sendMessageFormat(languageContext.get("general.mention_user_required"), EmoteReference.ERROR).queue();
                    return;
                }

                User giveTo = event.getMessage().getMentionedUsers().get(0);

                if(giveTo.equals(event.getAuthor())) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.transfer_yourself_note"), EmoteReference.THINKING).queue();
                    return;
                }

                if(giveTo.isBot()) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.bot_notice"), EmoteReference.ERROR).queue();
                    return;
                }

                if(!handleDefaultRatelimit(rl, event.getAuthor(), event)) return;

                if(Items.fromAnyNoId(args[0]) != null) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.item_transfer"), EmoteReference.ERROR).queue();
                    return;
                }

                long toSend; // = 0 at the start

                try {
                    //Convert negative values to absolute.
                    toSend = Math.abs(Long.parseLong(args[1]));
                } catch(Exception e) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.no_amount"), EmoteReference.ERROR).queue();
                    return;
                }

                if(toSend == 0) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.no_money_specified_notice"), EmoteReference.ERROR).queue();
                    return;
                }

                if(toSend > TRANSFER_LIMIT) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.over_transfer_limit"),
                            EmoteReference.ERROR, TRANSFER_LIMIT).queue();
                    return;
                }

                Player transferPlayer = MantaroData.db().getPlayer(event.getMember());
                Player toTransfer = MantaroData.db().getPlayer(event.getGuild().getMember(giveTo));

                if(transferPlayer.isLocked()) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.own_locked_notice"), EmoteReference.ERROR).queue();
                    return;
                }

                if(transferPlayer.getMoney() < toSend) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.no_money_notice"), EmoteReference.ERROR).queue();
                    return;
                }

                if(toTransfer.isLocked()) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.receipt_locked_notice"), EmoteReference.ERROR).queue();
                    return;
                }

                if(toTransfer.getMoney() > (long) TRANSFER_LIMIT * 20) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.receipt_over_limit"), EmoteReference.ERROR).queue();
                    return;
                }

                if(toTransfer.addMoney(toSend)) {
                    transferPlayer.removeMoney(toSend);
                    transferPlayer.saveAsync();

                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.success"), EmoteReference.CORRECT, toSend,
                            event.getMessage().getMentionedUsers().get(0).getName()).queue();

                    toTransfer.saveAsync();
                    rl.process(toTransfer.getUserId());
                } else {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.transfer.receipt_overflow_notice"), EmoteReference.ERROR).queue();
                }
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Transfer command")
                        .setDescription("**Transfers money from you to another player.**")
                        .addField("Usage", "`~>transfer <@user> <money>` - **Transfers money to x player**", false)
                        .addField("Parameters", "`@user` - user to send money to\n" +
                                "`money` - money to transfer.", false)
                        .addField("Important", "You cannot send more money than what you already have\n" +
                                "The maximum amount you can transfer at once is " + TRANSFER_LIMIT + " credits.", false)
                        .build();
            }
        });
    }


    @Subscribe
    public void lootcrate(CommandRegistry registry) {
        final RateLimiter ratelimiter = new RateLimiter(TimeUnit.MINUTES, 15);
        registry.register("opencrate", new SimpleCommand(Category.CURRENCY) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                Player p = MantaroData.db().getPlayer(event.getAuthor());
                if(!p.getInventory().containsItem(Items.LOOT_CRATE)) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.opencrate.no_crate"), EmoteReference.SAD).queue();
                    return;
                }

                if(!Utils.handleDefaultRatelimit(ratelimiter, event.getAuthor(), event))
                    return;

                Items.LOOT_CRATE.getAction().test(event, languageContext);
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Open loot crates")
                        .setDescription("**Yep. It's really that simple**.\n" +
                                "You need a crate key to open a loot crate. Loot crates are acquired rarely from the loot command.")
                        .build();
            }
        });
    }

    @Subscribe
    public void useItem(CommandRegistry cr) {
        cr.register("useitem", new SimpleCommand(Category.CURRENCY) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                if(args.length < 1) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.useitem.no_items_specified"), EmoteReference.ERROR).queue();
                    return;
                }

                if(args[0].equalsIgnoreCase("ils")) {
                    //TODO: List interactive items.
                    return;
                }

                Item item = Items.fromAnyNoId(content).orElse(null);
                if(item == null) {
                    event.getChannel().sendMessageFormat(languageContext.get("general.item_lookup.not_found"), EmoteReference.ERROR).queue();
                    return;
                }

                if(item.getItemType() != ItemType.INTERACTIVE) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.useitem.not_interactive"), EmoteReference.ERROR).queue();
                    return;
                }

                if(item == Items.BROM_PICKAXE || item == Items.FISHING_ROD) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.useitem.use_command"), EmoteReference.WARNING).queue();
                    return;
                }

                if(item.getAction() == null) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.useitem.interactive_no_action"), EmoteReference.ERROR).queue();
                    return;
                }

                Player p = MantaroData.db().getPlayer(event.getAuthor());
                if(!p.getInventory().containsItem(item)) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.useitem.no_item"), EmoteReference.SAD).queue();
                    return;
                }

                item.getAction().test(event, languageContext);
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Use Item Command")
                        .setDescription("**Uses an item**\n" +
                                "You need to have the item to use it, and the item has to be marked as *interactive*. For a list of interactive items use" +
                                "`~>useitem ils`")
                        .addField("Usage", "`~>useitem <item>` - **Uses the specified item**", false)
                        .addField("Example", "`~>useitem fishing rod`", false)
                        .build();
            }
        });
    }

    @Subscribe
    public void fish(CommandRegistry cr) {
        final RateLimiter ratelimiter = new RateLimiter(TimeUnit.MINUTES, 5);
        cr.register("fish", new SimpleCommand(Category.CURRENCY) {
            @Override
            protected void call(GuildMessageReceivedEvent event, I18nContext languageContext, String content, String[] args) {
                Player p = MantaroData.db().getPlayer(event.getAuthor());
                if(!p.getInventory().containsItem(Items.FISHING_ROD)) {
                    event.getChannel().sendMessageFormat(languageContext.get("commands.fish.no_rod"), EmoteReference.SAD).queue();
                    return;
                }

                if(!Utils.handleDefaultRatelimit(ratelimiter, event.getAuthor(), event))
                    return;

                Items.FISHING_ROD.getAction().test(event, languageContext);
            }

            @Override
            public MessageEmbed help(GuildMessageReceivedEvent event) {
                return helpEmbed(event, "Fish Command")
                        .setDescription("**Starts a fishing session**\n" +
                                "You need a fishing rod to start fishing. The rod has a 20% chance of breaking (10% if you use an stamina potion)")
                        .build();
            }
        });
    }
}

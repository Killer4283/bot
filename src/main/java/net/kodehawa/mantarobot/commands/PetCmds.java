/*
 * Copyright (C) 2016-2020 David Rubio Escares / Kodehawa
 *
 *  Mantaro is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *  Mantaro is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Mantaro.  If not, see http://www.gnu.org/licenses/
 */

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.api.EmbedBuilder;
import net.kodehawa.mantarobot.commands.currency.item.ItemStack;
import net.kodehawa.mantarobot.commands.currency.item.Items;
import net.kodehawa.mantarobot.commands.currency.item.special.Food;
import net.kodehawa.mantarobot.commands.currency.pets.HousePet;
import net.kodehawa.mantarobot.commands.currency.pets.HousePetType;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.listeners.operations.InteractiveOperations;
import net.kodehawa.mantarobot.core.listeners.operations.core.Operation;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.SubCommand;
import net.kodehawa.mantarobot.core.modules.commands.TreeCommand;
import net.kodehawa.mantarobot.core.modules.commands.base.Command;
import net.kodehawa.mantarobot.core.modules.commands.base.CommandCategory;
import net.kodehawa.mantarobot.core.modules.commands.base.Context;
import net.kodehawa.mantarobot.core.modules.commands.help.HelpContent;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.commands.ratelimit.IncreasingRateLimiter;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Module
public class PetCmds {
    @Subscribe
    public void pet(CommandRegistry cr) {
        IncreasingRateLimiter rl = new IncreasingRateLimiter.Builder()
                .limit(1)
                .spamTolerance(2)
                .cooldown(5, TimeUnit.SECONDS)
                .maxCooldown(5, TimeUnit.SECONDS)
                .randomIncrement(true)
                .pool(MantaroData.getDefaultJedisPool())
                .prefix("pet")
                .build();

        TreeCommand pet = (TreeCommand) cr.register("pet", new TreeCommand(CommandCategory.CURRENCY) {
            @Override
            public Command defaultTrigger(Context ctx, String mainCommand, String commandName) {
                return new SubCommand() {
                    @Override
                    protected void call(Context ctx, String content) {
                        ctx.sendLocalized("commands.pet.explanation");
                    }
                };
            }

            @Override
            public HelpContent help() {
                return new HelpContent.Builder()
                        // TODO: wiki page.
                        .setDescription("Pet commands. For a better explanation of the pet system see [here]().")
                        .build();
            }
        });

        cr.registerAlias("pet", "pets");
        pet.setPredicate(ctx -> Utils.handleIncreasingRatelimit(rl, ctx.getAuthor(), ctx.getEvent(), null, false));

        pet.addSubCommand("status", new SubCommand() {
            @Override
            public String description() {
                return "Shows the status of your current pet.";
            }

            @Override
            protected void call(Context ctx, String content) {
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();
                var pet = marriage.getData().getPet();

                if(marriage == null || pet == null) {
                    ctx.sendLocalized("commands.pet.status.no_pet_or_marriage");
                    return;
                }

                var language = ctx.getLanguageContext();

                EmbedBuilder status = new EmbedBuilder()
                        .setAuthor(String.format(language.get("commands.pet.status.header"), pet.getName()), ctx.getUser().getEffectiveAvatarUrl())
                        .setDescription(language.get("commands.pet.status.description"))
                        .addField(
                                EmoteReference.MONEY + "commands.pet.status.cost",
                                String.valueOf(pet.getType().getCost()),
                                true
                        )
                        .addField(
                                EmoteReference.ZAP + "commands.pet.status.type",
                                pet.getType().getEmoji() + pet.getType().getName(),
                                true
                        )
                        .addField(
                                EmoteReference.WRENCH + "commands.pet.status.abilities",
                                pet.getType().getStringAbilities(),
                                false
                        )
                        .addField(
                                language.get(EmoteReference.ZAP + "commands.pet.status.level"),
                                pet.getLevel() + "(XP: " + pet.getExperience() + ")\n" +
                                        Utils.getProgressBar(pet.getExperience(), (long) pet.experienceToNextLevel(), 5),
                                true
                        )
                        .addField(
                                language.get(EmoteReference.HEART + "commands.pet.status.health"),
                                pet.getHealth() + " / 100\n" + Utils.getProgressBar(pet.getHealth(), 100, 5),
                                false
                        )
                        .addField(
                                language.get(EmoteReference.DROPLET + "commands.pet.status.thrist"),
                                pet.getThirst() + " / 100\n" + Utils.getProgressBar(pet.getThirst(), 100, 5),
                                false
                        )
                        .addField(
                                language.get(EmoteReference.CHOCOLATE + "commands.pet.status.hunger"),
                                pet.getHunger() + " / 100\n" + Utils.getProgressBar(pet.getHealth(), 100, 5),
                                false
                        )
                        .addField(
                                language.get(EmoteReference.BLUE_HEART + "commands.pet.status.pet"),
                                String.valueOf(pet.getPatCounter()),
                                false
                        )
                        .setFooter(language.get("commands.pet.status.footer"));

                ctx.send(status.build());
            }
        });

        pet.addSubCommand("pet", new SubCommand() {
            @Override
            public String description() {
                return "Pets your pet. Usage: `~>pet pet`. Cute.";
            }

            @Override
            protected void call(Context ctx, String content) {
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();
                var pet = marriage.getData().getPet();

                if(pet == null) {
                    ctx.sendLocalized("commands.pet.pat.no_pet");
                    return;
                }

                String message = pet.handlePat().getMessage();
                String extraMessage = "";
                if(pet.getPatCounter() > 100) {
                    extraMessage += "\n" + ctx.getLanguageContext().get("commands.pet.pet_reactions.counter_100");
                }

                pet.increasePats();
                marriage.save();

                ctx.sendLocalized(message, pet.getType().getEmoji(), pet.getPatCounter() + extraMessage);
            }
        });

        pet.addSubCommand("buy", new SubCommand() {
            @Override
            public String description() {
                return "Buys a pet to have adventures with. You need to buy a pet house in market first. Usage: `~>pet buy <type> <name>`";
            }

            @Override
            protected void call(Context ctx, String content) {
                var player = ctx.getPlayer();
                var playerInventory = player.getInventory();
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();
                var marriageData = marriage.getData();

                var args = ctx.getArguments();

                // TODO: personal pets?
                if(marriage == null) {
                    ctx.sendLocalized("commands.marry.general.not_married", EmoteReference.ERROR);
                    return;
                }

                if(args.length < 2) {
                    ctx.sendLocalized("commands.pet.buy.not_enough_arguments", EmoteReference.ERROR);
                    return;
                }

                var name = args[0];
                var type = args[1];

                if(!marriageData.hasCar() || !marriageData.hasHouse()) {
                    ctx.sendLocalized("commands.pet.buy.no_requirements", EmoteReference.ERROR, marriageData.hasHouse(), marriageData.hasCar());
                    return;
                }

                if(!playerInventory.containsItem(Items.PET_HOUSE)) {
                    ctx.sendLocalized("commands.pet.buy.no_house", EmoteReference.ERROR);
                    return;
                }

                // TODO: Multiple pets.
                if(marriageData.getPet() != null) {
                    ctx.sendLocalized("commands.pet.buy.already_has_pet", EmoteReference.ERROR);
                    return;
                }

                HousePetType toBuy = HousePetType.lookupFromString(type);
                if(toBuy == null) {
                    ctx.sendLocalized("commands.pet.buy.nothing_found", EmoteReference.ERROR,
                            Arrays.stream(HousePetType.values()).map(HousePetType::getName).collect(Collectors.joining(", "))
                    );
                    return;
                }

                if(player.getCurrentMoney() < toBuy.getCost()) {
                    ctx.sendLocalized("commands.pet.buy.not_enough_money", EmoteReference.ERROR, toBuy.getCost(), player.getCurrentMoney());
                    return;
                }


                ctx.sendLocalized("commands.pet.buy.confirm", EmoteReference.WARNING, name, type, toBuy.getCost());
                InteractiveOperations.create(ctx.getChannel(), ctx.getAuthor().getIdLong(), 30, (e) -> {
                    if (!e.getAuthor().equals(ctx.getAuthor()))
                        return Operation.IGNORED;

                    if(e.getMessage().getContentRaw().equalsIgnoreCase("yes")) {
                        var playerConfirmed = ctx.getPlayer();
                        var playerInventoryConfirmed = playerConfirmed.getInventory();
                        var dbUserConfirmed = ctx.getDBUser();
                        var marriageConfirmed = dbUserConfirmed.getData().getMarriage();

                        // People like to mess around lol.
                        if(!playerInventory.containsItem(Items.PET_HOUSE)) {
                            ctx.sendLocalized("commands.pet.buy.no_house", EmoteReference.ERROR);
                            return Operation.COMPLETED;
                        }

                        if(player.getCurrentMoney() < toBuy.getCost()) {
                            ctx.sendLocalized("commands.pet.buy.not_enough_money", EmoteReference.ERROR, toBuy.getCost());
                            return Operation.COMPLETED;
                        }

                        playerConfirmed.removeMoney(toBuy.getCost());
                        playerInventoryConfirmed.process(new ItemStack(Items.PET_HOUSE, -1));
                        playerConfirmed.save();

                        marriageConfirmed.getData().setPet(new HousePet(name, toBuy));
                        marriageConfirmed.save();

                        ctx.sendLocalized("commands.pet.buy.success", EmoteReference.POPPER, toBuy.getEmoji(), toBuy.getName(), content, toBuy.getCost());
                        return Operation.COMPLETED;
                    }

                    if(e.getMessage().getContentRaw().equalsIgnoreCase("no")) {
                        ctx.sendLocalized("commands.pet.buy.cancel_success", EmoteReference.CORRECT);
                        return Operation.COMPLETED;
                    }

                    return Operation.IGNORED;
                });
            }
        });

        pet.addSubCommand("rename", new SubCommand() {
            @Override
            public String description() {
                return "Renames your pet. Usage: `~>pet rename <name>`";
            }

            @Override
            protected void call(Context ctx, String content) {
                var player = ctx.getPlayer();
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();
                var pet = marriage.getData().getPet();
                var cost = 3000;

                if(marriage == null) {
                    ctx.sendLocalized("commands.marry.general.not_married", EmoteReference.ERROR);
                    return;
                }

                if(pet == null) {
                    ctx.sendLocalized("commands.pet.rename.no_pet", EmoteReference.ERROR);
                    return;
                }

                if(content.isEmpty()) {
                    ctx.sendLocalized("commands.pet.rename.no_content", EmoteReference.ERROR);
                    return;
                }

                if(player.getCurrentMoney() < cost) {
                    ctx.sendLocalized("commands.pet.rename.not_enough_money", EmoteReference.ERROR, cost, player.getCurrentMoney());
                    return;
                }

                var oldName = pet.getName();
                pet.setName(content);
                marriage.save();
                player.save();

                ctx.sendLocalized("commands.pet.rename.success", EmoteReference.POPPER, oldName, content, cost);
            }
        });

        pet.addSubCommand("feed", new SubCommand() {
            @Override
            public String description() {
                return "Feeds your pet. Types of food may vary per pet. Usage: `~>pet feed <food>`";
            }

            @Override
            protected void call(Context ctx, String content) {
                var player = ctx.getPlayer();
                var playerInventory = player.getInventory();
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();
                var pet = marriage.getData().getPet();

                if(marriage == null) {
                    ctx.sendLocalized("commands.marry.general.not_married", EmoteReference.ERROR);
                    return;
                }

                if(pet == null) {
                    ctx.sendLocalized("commands.pet.feed.no_pet", EmoteReference.ERROR);
                    return;
                }

                if(content.isEmpty()) {
                    ctx.sendLocalized("commands.pet.feed.no_content", EmoteReference.ERROR);
                    return;
                }

                var item = Items.fromAnyNoId(content);
                if(item.isEmpty()) {
                    ctx.sendLocalized("commands.pet.feed.no_item", EmoteReference.ERROR);
                    return;
                }

                var itemObject = item.get();
                if(!(itemObject instanceof Food)) {
                    ctx.sendLocalized("commands.pet.feed.not_food", EmoteReference.ERROR);
                    return;
                }

                if(pet.getHunger() > 90) {
                    ctx.sendLocalized("commands.pet.feed.no_need", EmoteReference.ERROR);
                    return;
                }

                if(playerInventory.containsItem(itemObject)) {
                    ctx.sendLocalized("commands.pet.feed.not_inventory", EmoteReference.ERROR);
                    return;
                }

                var food = (Food) itemObject;

                if(food.getType().getApplicableType() != pet.getType()) {
                    ctx.sendLocalized("commands.pet.feed.not_applicable", EmoteReference.ERROR);
                    return;
                }

                pet.increaseHunger(food.getHungerLevel());
                pet.increaseHealth();
                pet.increaseStamina();

                playerInventory.process(new ItemStack(itemObject, -1));
                player.save();

                marriage.save();
                ctx.sendLocalized("commands.pet.feed.success", EmoteReference.ERROR, food.getName(), food.getHungerLevel(), pet.getHunger());
            }
        });

        pet.addSubCommand("water", new SubCommand() {
            @Override
            public String description() {
                return "Waters your pet.";
            }

            @Override
            protected void call(Context ctx, String content) {
                var player = ctx.getPlayer();
                var playerInventory = player.getInventory();
                var dbUser = ctx.getDBUser();
                var marriage = dbUser.getData().getMarriage();
                var pet = marriage.getData().getPet();

                if(marriage == null) {
                    ctx.sendLocalized("commands.marry.general.not_married", EmoteReference.ERROR);
                    return;
                }

                if(pet == null) {
                    ctx.sendLocalized("commands.pet.water.no_pet", EmoteReference.ERROR);
                    return;
                }

                if(pet.getThirst() > 90) {
                    ctx.sendLocalized("commands.pet.water.no_need", EmoteReference.ERROR);
                    return;
                }

                var item = Items.WATER_BOTTLE;
                if(playerInventory.containsItem(item)) {
                    ctx.sendLocalized("commands.pet.water.not_inventory", EmoteReference.ERROR);
                    return;
                }

                pet.increaseThirst();
                pet.increaseHealth();
                pet.increaseStamina();

                playerInventory.process(new ItemStack(item, -1));
                player.save();

                marriage.save();
                ctx.sendLocalized("commands.pet.water.success", EmoteReference.ERROR, 15, pet.getThirst());
            }
        });
    }
}

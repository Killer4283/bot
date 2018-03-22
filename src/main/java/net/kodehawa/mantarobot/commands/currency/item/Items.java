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

package net.kodehawa.mantarobot.commands.currency.item;

import lombok.extern.slf4j.Slf4j;
import net.dv8tion.jda.core.events.message.guild.GuildMessageReceivedEvent;
import net.kodehawa.mantarobot.commands.currency.profile.Badge;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.db.entities.Player;
import net.kodehawa.mantarobot.db.entities.helpers.Inventory;
import net.kodehawa.mantarobot.utils.RandomCollection;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.commands.RateLimiter;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static net.kodehawa.mantarobot.utils.Utils.handleDefaultRatelimit;

@Slf4j
public class Items {
    public static final Item HEADPHONES, BAN_HAMMER, KICK_BOOT, FLOPPY_DISK, MY_MATHS, PING_RACKET,
            LOADED_DICE, FORGOTTEN_MUSIC, CC_PENCIL, OVERFLOWED_BAG, BROM_PICKAXE, MILK, ALCOHOL, LEWD_MAGAZINE, RING,
            LOOT_CRATE_KEY,
            BOOSTER, BERSERK, ENHANCER, RING_2, COMPANION, LOADED_DICE_2, LOVE_LETTER, CLOTHES, SHOES, DIAMOND, CHOCOLATE, COOKIES,
            NECKLACE, ROSE,
            DRESS, TUXEDO, LOOT_CRATE, STAR, STAR_2, SLOT_COIN, HOUSE, CAR, BELL_SPECIAL, CHRISTMAS_TREE_SPECIAL, PANTS, POTION_HASTE, POTION_CLEAN, POTION_STAMINA, FISHING_ROD,
            FISH_1, FISH_2, FISH_3, GEM_1, GEM_2, GEM_3, GEM_4;

    private static final Random r = new Random();
    private static final RateLimiter lootCrateRatelimiter = new RateLimiter(TimeUnit.HOURS, 1);

    public static final Item[] ALL = {
            HEADPHONES = new Item(ItemType.COLLECTABLE, "\uD83C\uDFA7", "Headphones", "That's what happens when you listen to too much music. Should be worth something, tho.", 5, true, false),
            BAN_HAMMER = new Item(ItemType.COLLECTABLE, "\uD83D\uDD28", "Ban Hammer", "Left by an admin. +INF Dmg", 15, false),
            KICK_BOOT = new Item(ItemType.COLLECTABLE, "\uD83D\uDC62", "Kick Boot", "Left by an admin. +INF Knockback", 12, false),
            FLOPPY_DISK = new Item(ItemType.COLLECTABLE, "\uD83D\uDCBE", "Floppy Disk", "Might have some games.", 13, false),
            MY_MATHS = new Item(ItemType.COLLECTABLE, "\uD83D\uDCDD", "My Maths", "\"Oh, I forgot my maths.\"", 11, false),
            PING_RACKET = new Item(ItemType.COLLECTABLE, "\uD83C\uDFD3", "Ping Racket", "I won the game of ping-pong with Discord by a few milliseconds.", 15, false),
            LOADED_DICE = new Item(ItemType.COLLECTABLE, "\uD83C\uDFB2", "Loaded Die", "Stolen from `~>roll` command", 60, false),
            FORGOTTEN_MUSIC = new Item(ItemType.COLLECTABLE, "\uD83C\uDFB5", "Forgotten Music", "Never downloaded. Probably has been copyrighted.", 15, false),
            CC_PENCIL = new Item(ItemType.COLLECTABLE, "\u270f", "Pencil", "We have plenty of those!", 15, false),
            OVERFLOWED_BAG = new Item(ItemType.COLLECTABLE, "\uD83D\uDCB0","Moneybag", "What else?.", 95, true),
            BROM_PICKAXE = new Item(ItemType.INTERACTIVE, "\u26cf","Brom's Pickaxe", "That guy liked Minecraft way too much. (`~>mine` tool)", 75, true),
            MILK = new Item(ItemType.COMMON, EmoteReference.POTION1.getUnicode(),"Milk", "Maybe it's okay to have some.", 45, true),
            ALCOHOL = new Item(ItemType.COMMON, EmoteReference.POTION2.getUnicode(),"Alcohol", "Does really weird stuff in big quantities.", 45, true),
            LEWD_MAGAZINE = new Item(ItemType.COMMON, EmoteReference.MAGAZINE.getUnicode(),"Lewd Magazine", "Too many lewd commands.", 45, true),
            RING = new Item(ItemType.COMMON, EmoteReference.RING.getUnicode(),"Marriage Ring", "Basically what makes your marriage official", 60, true),
            LOVE_LETTER = new Item(ItemType.COLLECTABLE, EmoteReference.LOVE_LETTER.getUnicode(),"Love Letter", "A letter from your beloved one.", 45, false),
            LOOT_CRATE_KEY = new Item(ItemType.COMMON, EmoteReference.KEY.getUnicode(),"Crate Key", "Used to open loot boxes with `~>opencrate` or `~>useitem loot crate`", 58, true),
            CLOTHES = new Item(ItemType.COMMON, EmoteReference.CLOTHES.getUnicode(),"Clothes", "Basically what you wear.", 15, true),
            DIAMOND = new Item(ItemType.COMMON, EmoteReference.DIAMOND.getUnicode(),"Diamond", "Basically a better way of saving your money. It's shiny too.", 350, true),
            DRESS = new Item(ItemType.COMMON, EmoteReference.DRESS.getUnicode(),"Wedding Dress", "Isn't it cute?", 75, true),
            NECKLACE = new Item(ItemType.COMMON, EmoteReference.NECKLACE.getUnicode(),"Necklace", "Looks nice.", 17, true),
            TUXEDO = new Item(ItemType.COMMON, EmoteReference.TUXEDO.getUnicode(),"Tuxedo", "What you wear when you're going to get married with a girl.", 24, true),
            SHOES = new Item(ItemType.COMMON, EmoteReference.SHOES.getUnicode(),"Shoes", "Cause walking barefoot is just nasty.", 9, true),
            ROSE = new Item(ItemType.COMMON, EmoteReference.ROSE.getUnicode(),"Rose", "The embodiment of your love.", 53, true),
            CHOCOLATE = new Item(ItemType.COMMON, EmoteReference.CHOCOLATE.getUnicode(),"Chocolate", "Yummy.", 45, true),
            COOKIES = new Item(ItemType.COMMON, EmoteReference.COOKIE.getUnicode(),"Cookie", "Delicious.", 48, true),

            // ---------------------------------- LEFT OVERS FROM CURRENCY V1 STARTS HERE ----------------------------------
            //CANNOT REMOVE BECAUSE WE WERE MEME ENOUGH TO FUCKING SAVE THEM BY THEIR IDS
            LOADED_DICE_2 = new Item("\uD83C\uDFB2","Special Loaded Die", "Even more loaded. `Leftover from Currency version 1. No longer obtainable.`"),
            BOOSTER = new Item(EmoteReference.RUNNER.getUnicode(),"Booster", "Used to give you some kind of boost, now it's broken. `Leftover from Currency version 1. No longer obtainable.`"),
            BERSERK = new Item(EmoteReference.CROSSED_SWORD.getUnicode(),"Berserk", "Currency Berserker? Anyone? `Leftover from Currency version 1. No longer obtainable.`"),
            COMPANION = new Item(EmoteReference.DOG.getUnicode(),"Companion", "Aw, such a cute dog. `Leftover from Currency version 1. No longer obtainable.`"),
            RING_2 = new Item("\uD83D\uDC5A","Special Ring", "It's so special, it's not even a ring. `Leftover from Currency version 1. No longer obtainable.`"),
            ENHANCER = new Item(EmoteReference.MAG.getUnicode(),"Enchancer", "A broken enchanter, I wonder if it could be fixed? `Leftover from Currency version 1. No longer obtainable.`"),
            STAR = new Item(ItemType.COLLECTABLE, EmoteReference.STAR.getUnicode(),"Prize", "Pretty much, huh? `Leftover from Currency version 1. No longer obtainable.`", 0, false, false, true),

            // ---------------------------------- LEFT OVERS FROM CURRENCY V1 END HERE ----------------------------------
            LOOT_CRATE = new Item(ItemType.INTERACTIVE, EmoteReference.LOOT_CRATE.getDiscordNotation(),"Loot Crate", "You can use this along with a loot key to open a loot crate! `~>opencrate`", 0, false, false, true, Items::openLootCrate),
            STAR_2 = new Item(ItemType.COMMON, EmoteReference.STAR.getUnicode(),"Prize 2", "In the first place, how did you get so much money?", 500, true, false, true),
            SLOT_COIN = new Item(ItemType.COMMON, "\uD83C\uDF9F","Slot ticket", "Gives you extra chance in slots, also works as bulk storage.", 65, true, true),
            HOUSE = new Item(ItemType.COMMON, EmoteReference.HOUSE.getUnicode(),"House", "Cozy place to live in.", 5000, true, true),
            CAR = new Item(ItemType.COMMON, "\uD83D\uDE97","Car", "To move around.", 1000, true, true),

            // ---------------------------------- CHRISTMAS 2017 EVENT STARTS HERE ----------------------------------
            BELL_SPECIAL = new Item(ItemType.RARE, "\uD83D\uDD14", "Christmas bell","Christmas event 2017 reward. Gives you a cozy christmas feeling on your tree.", 0, false, false, true),
            CHRISTMAS_TREE_SPECIAL = new Item(ItemType.RARE, "\uD83C\uDF84", "Christmas tree","Christmas event 2017 reward. Who doesn't like a christmas tree?.", 0, false, false, true),
            // ---------------------------------- CHRISTMAS 2017 EVENT ENDS HERE ----------------------------------

            // ---------------------------------- 5.0 ITEMS START HERE ----------------------------------
            PANTS = new Item(ItemType.COMMON, "\uD83D\uDC56", "Pants", "Basically what you wear on your legs... hopefully.", 20, true),
            POTION_HASTE = new Item(ItemType.RARE, EmoteReference.POTION1.getUnicode(),"Haste Potion", "Allows you to have 50% less ratelimit effect on some commands for 5 minutes.", 45, true),
            POTION_CLEAN = new Item(ItemType.INTERACTIVE, EmoteReference.POTION1.getUnicode(),"Milk Potion", "Clears all potion effects.", 45, true),
            POTION_STAMINA = new Item(ItemType.INTERACTIVE, EmoteReference.POTION2.getUnicode(),"Energy Beverage", "Gives less chance of a pick breaking while mining. Lasts only 5 mining sessions.", 45, true),
            FISHING_ROD = new Item(ItemType.INTERACTIVE, "\uD83C\uDFA3","Fishing Rod", "Enables you to fish.", 65, true),
            FISH_1 = new Item(ItemType.COMMON, "\uD83D\uDC1F","Fish", "Common Fish. Caught in fishing", 10, false),
            FISH_2 = new Item(ItemType.COMMON, "\uD83D\uDC20","Tropical Fish", "Rare Fish. Caught in fishing", 30, false),
            FISH_3 = new Item(ItemType.RARE, "\uD83D\uDC21","Blowfish", "Rarest Fish. You're extremely lucky if you actually got this.", 45, false),
            // ---------------------------------- 5.0 MINING ITEMS START HERE ----------------------------------
            GEM_1 = new Item(ItemType.COMMON, "\u2604", "Comet Gem", "Fragments of a comet you found while mining. Useful for casting.", 40, false),
            GEM_2 = new Item(ItemType.COMMON, EmoteReference.STAR.getUnicode(), "Star Gem", "Fragments of a fallen star you found while mining.", 45, false),
            GEM_3 = new Item(ItemType.COMMON, "\uD83D\uDD78", "Cobweb", "Something a spider left over on the mine. Wonder if it's worth something.", 10, false),
            GEM_4 = new Item(ItemType.COMMON, "\uD83D\uDCAB", "Gem Fragment", "Fragment of an ancient gem. Useful for casting", 50, false)
    };

    public static void setItemActions() {
        final SecureRandom random = new SecureRandom();
        log.info("Registering item actions...");
        FISHING_ROD.setAction((event, lang) -> {
            Player p = MantaroData.db().getPlayer(event.getAuthor());
            Inventory playerInventory = p.getInventory();

            if(!playerInventory.containsItem(FISHING_ROD))
                return false;

            //TODO plz repeat less code
            //yes this uses a different random than the other thing
            if(r.nextInt(100) > (handleStaminaPotion(p) ? 90 : 80)) { //20% chance for the rod to break on usage (10% with stamina).
                event.getChannel().sendMessageFormat(lang.get("commands.fish.rod_broke"), EmoteReference.SAD).queue();
                playerInventory.process(new ItemStack(FISHING_ROD, -1));
                p.save();
                return false;
            } else {
                int select = random.nextInt(100);

                if(select < 25) {
                    //we need to continue the dust meme
                    event.getChannel().sendMessageFormat(lang.get("commands.fish.dust"), EmoteReference.TALKING).queue();
                } else if(select < 45) {
                    List<Item> common = Stream.of(ALL)
                            .filter(i -> i.getItemType() == ItemType.COMMON && !i.isHidden() && i.isSellable() && i.value < 45)
                            .collect(Collectors.toList());
                    Item selected = common.get(random.nextInt(common.size()));
                    if(playerInventory.getAmount(selected) == 5000) {
                        event.getChannel().sendMessageFormat(lang.get("commands.fish.trash.overflow"), EmoteReference.SAD).queue();
                        return true;
                    }

                    playerInventory.process(new ItemStack(selected, 1));
                    event.getChannel().sendMessageFormat(lang.get("commands.fish.trash.success"), EmoteReference.EYES, selected.getEmoji(), selected.getName()).queue();
                } else if (select < 65) {
                    int amount = random.nextInt(4);

                    if(playerInventory.getAmount(FISH_1) + amount >= 5000) {
                        event.getChannel().sendMessageFormat(lang.get("commands.fish.overflow"), EmoteReference.SAD).queue();
                        return true;
                    }

                    playerInventory.process(new ItemStack(FISH_1, amount));
                    event.getChannel().sendMessageFormat(lang.get("commands.fish.success"), EmoteReference.POPPER, amount, FISH_1.getEmoji()).queue();
                } else if (select < 85) {
                    int amount = random.nextInt(2);

                    if(playerInventory.getAmount(FISH_2) + amount >= 5000) {
                        event.getChannel().sendMessageFormat(lang.get("commands.fish.overflow"), EmoteReference.SAD).queue();
                        return true;
                    }


                    playerInventory.process(new ItemStack(FISH_2, amount));
                    event.getChannel().sendMessageFormat(lang.get("commands.fish.success"), EmoteReference.POPPER, amount, FISH_2.getEmoji()).queue();
                } else {
                    int amount = random.nextInt(2);
                    Item selected = null;
                    if(random.nextInt(25) > 20) {
                        List<Item> rare = Stream.of(ALL)
                                .filter(i -> i.getItemType() == ItemType.RARE && !i.isHidden() && i.isSellable())
                                .collect(Collectors.toList());
                        selected = rare.get(random.nextInt(rare.size()));
                    }

                    if(playerInventory.getAmount(FISH_3) + amount >= 5000 ) {
                        event.getChannel().sendMessageFormat(lang.get("commands.fish.overflow"), EmoteReference.SAD).queue();
                        return true;
                    }

                    playerInventory.process(new ItemStack(FISH_3, amount));
                    event.getChannel().sendMessageFormat(lang.get("commands.fish.success"),
                            EmoteReference.POPPER, amount, FISH_3.getEmoji(), (selected != null ? "\n" + String.format(lang.get("commands.fish.tier3.extra"), selected) : "")
                    ).queue();
                }

                p.save();
                return true;
            }
        });

        BROM_PICKAXE.setAction((event, lang) -> {
            Player p = MantaroData.db().getPlayer(event.getAuthor());
            Inventory playerInventory = p.getInventory();

            //Defensive programming :D
            if(!playerInventory.containsItem(BROM_PICKAXE))
                return false;

            if(r.nextInt(100) > (handleStaminaPotion(p) ? 85 : 75)) { //35% chance for the pick to break on usage (25% with stamina).
                event.getChannel().sendMessageFormat(lang.get("commands.mine.pick_broke"), EmoteReference.SAD).queue();
                playerInventory.process(new ItemStack(BROM_PICKAXE, -1));
                p.save();
                return false;
            } else {
                return true;
            }
        });

        POTION_CLEAN.setAction((event, lang) -> {
            Player p = MantaroData.db().getPlayer(event.getAuthor());
            p.getData().setActivePotion(null);
            event.getChannel().sendMessage(EmoteReference.POPPER + "Cleared potion effects.").queue();
            p.getInventory().process(new ItemStack(POTION_CLEAN, -1));
            p.save();
            return true;
        });

        POTION_STAMINA.setAction((event, lang) -> {
            Player p = MantaroData.db().getPlayer(event.getAuthor());
            p.getData().setActivePotion(new PotionEffect(idOf(POTION_STAMINA), System.currentTimeMillis(), ItemType.PotionType.PLAYER));
            event.getChannel().sendMessage(EmoteReference.POPPER + "Activated stamina for 5 mining sessions.").queue();
            p.getInventory().process(new ItemStack(POTION_STAMINA, -1));
            p.save();
            return true;
        });

        POTION_HASTE.setAction((event, lang) -> {
            Player p = MantaroData.db().getPlayer(event.getAuthor());
            p.getData().setActivePotion(new PotionEffect(idOf(POTION_HASTE),
                    System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(2), ItemType.PotionType.PLAYER));
            event.getChannel().sendMessage(EmoteReference.POPPER + "Activated Haste for 2 minutes.").queue();
            p.getInventory().process(new ItemStack(POTION_HASTE, -1));
            p.save();
            return true;
        });
    }

    public static Optional<Item> fromAny(String any) {
        try {
            Item item = fromId(Integer.parseInt(any));

            if(item != null)
                return Optional.of(item);
        } catch(NumberFormatException ignored) {}

        return fromAnyNoId(any);
    }

    public static Optional<Item> fromAnyNoId(String any) {
        Optional<Item> itemOptional;

        itemOptional = fromEmoji(any);
        if(itemOptional.isPresent())
            return itemOptional;

        itemOptional = fromName(any);
        if(itemOptional.isPresent())
            return itemOptional;

        itemOptional = fromPartialName(any);
        return itemOptional;
    }

    public static Optional<Item> fromEmoji(String emoji) {
        return Stream.of(ALL).filter(item -> item.getEmoji().equals(emoji)).findFirst();
    }

    public static Item fromId(int id) {
        return ALL[id];
    }

    public static Optional<Item> fromName(String name) {
        return Arrays.stream(ALL).filter(item -> item.getName().toLowerCase().trim().equals(name.toLowerCase().trim())).findFirst();
    }

    public static Optional<Item> fromPartialName(String name) {
        return Arrays.stream(ALL).filter(item -> item.getName().toLowerCase().trim().contains(name.toLowerCase().trim())).findFirst();
    }

    public static int idOf(Item item) {
        return Arrays.asList(ALL).indexOf(item);
    }

    private static boolean openLootCrate(GuildMessageReceivedEvent event, I18nContext lang) {
        Player player = MantaroData.db().getPlayer(event.getAuthor());
        Inventory inventory = player.getInventory();
        if(inventory.containsItem(Items.LOOT_CRATE)) {
            if(inventory.containsItem(Items.LOOT_CRATE_KEY)) {
                if(!handleDefaultRatelimit(lootCrateRatelimiter, event.getAuthor(), event))
                    return false;

                inventory.process(new ItemStack(Items.LOOT_CRATE_KEY, -1));
                inventory.process(new ItemStack(Items.LOOT_CRATE, -1));
                player.getData().addBadgeIfAbsent(Badge.THE_SECRET);
                player.save();
                openLootBox(event, true);
                return true;
            } else {
                event.getChannel().sendMessage(EmoteReference.ERROR + "You need a loot crate key to open a crate. It's locked!").queue();
                return false;
            }
        } else {
            event.getChannel().sendMessage(EmoteReference.ERROR + "You need a loot crate! How else would you use your key >.>").queue();
            return false;
        }
    }

    private static void openLootBox(GuildMessageReceivedEvent event, boolean special) {
        /*
        List<Item> toAdd = new ArrayList<>();
        int amtItems = r.nextInt(3) + 3;
        List<Item> items = new ArrayList<>(Arrays.asList(Items.ALL));
        items.removeIf(item -> item.isHidden() || !item.isBuyable() || !item.isSellable());
        items.sort(Comparator.comparingLong(Item::getValue));

        if(!special) {
            for(Item i : Items.ALL) if(i.isHidden() || !i.isBuyable() || i.isSellable()) items.add(i);
        }
        for(int i = 0; i < amtItems; i++)
            toAdd.add(selectReverseWeighted(items));
         */

        List<Item> toAdd = selectItems(r.nextInt(3) + 3, special ? ItemType.LootboxType.RARE : ItemType.LootboxType.COMMON);

        Player player = MantaroData.db().getPlayer(event.getMember());
        ArrayList<ItemStack> ita = new ArrayList<>();

        toAdd.forEach(item -> ita.add(new ItemStack(item, 1)));

        boolean overflow = player.getInventory().merge(ita);
        player.saveAsync();

        event.getChannel().sendMessage(String.format("%s**You won:** %s%s",
                EmoteReference.LOOT_CRATE.getDiscordNotation(), toAdd.stream().map(Item::toString).collect(Collectors.joining(", ")),
                overflow ? ". But you already had too much, so you decided to throw away the excess" : "")).queue();
    }

    /*
    private static Item selectReverseWeighted(List<Item> items) {
        Map<Integer, Item> weights = new HashMap<>();
        int weightedTotal = 0;

        for(int i = 0; i < items.size(); i++) {
            int t = items.size() - i;
            weightedTotal += t;
            weights.put(t, items.get(i));
        }

        final int[] selected = { r.nextInt(weightedTotal) };
        for(Map.Entry<Integer, Item> i : weights.entrySet()) {
            if((selected[0] -= i.getKey()) <= 0) {
                return i.getValue();
            }
        }
        return null;
    }
    */

    private static List<Item> selectItems(int amount, ItemType.LootboxType type) {
        List<Item> all = Arrays.stream(Items.ALL).filter(i->i.isBuyable() || i.isSellable()).collect(Collectors.toList());

        List<Item> common = all.stream()
                .filter(i->i.getItemType() == ItemType.COMMON)
                .sorted(Comparator.comparingLong(i->i.value))
                .collect(Collectors.toList());
        List<Item> rare = all.stream()
                .filter(i->i.getItemType() == ItemType.RARE)
                .sorted(Comparator.comparingLong(i->i.value))
                .collect(Collectors.toList());
        List<Item> premium = all.stream()
                .filter(i->i.getItemType() == ItemType.PREMIUM)
                .sorted(Comparator.comparingLong(i->i.value))
                .collect(Collectors.toList());

        RandomCollection<Item> items = new RandomCollection<>();

        //fallthrough intended
        switch(type) {
            case EPIC:
                throw new UnsupportedOperationException();
            case PREMIUM:
                premium.forEach(i->{
                    items.add(2, i);
                });
            case RARE:
                rare.forEach(i->{
                    items.add(5, i);
                });
            case COMMON:
                common.forEach(i->{
                    items.add(20, i);
                });
        }

        List<Item> list = new ArrayList<>(amount);
        for(int i = 0; i < amount; i++) {
            list.add(items.next());
        }

        return list;
    }

    private static boolean handleStaminaPotion(Player p) {
        boolean hasStaminaPotion = p.getData().getActivePotion() != null && fromId(p.getData().getActivePotion().getPotion()) == POTION_STAMINA;
        if (r.nextInt(100) > (hasStaminaPotion ? 85 : 75)) { //35% chance for the pick to break on usage (25% with stamina).
            if (hasStaminaPotion) {
                PotionEffect staminaPotion = p.getData().getActivePotion();
                //counter starts at 0
                if (staminaPotion.getTimesUsed() >= 4) {
                    p.getData().setActivePotion(null);
                    p.save();
                } else {
                    staminaPotion.setTimesUsed(staminaPotion.getTimesUsed() + 1);
                    p.save();
                }
            }
        }

        return hasStaminaPotion;
    }
}

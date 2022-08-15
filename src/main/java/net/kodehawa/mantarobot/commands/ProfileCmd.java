/*
 * Copyright (C) 2016-2022 David Rubio Escares / Kodehawa
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

package net.kodehawa.mantarobot.commands;

import com.google.common.eventbus.Subscribe;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.Modal;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.currency.item.ItemHelper;
import net.kodehawa.mantarobot.commands.currency.item.ItemReference;
import net.kodehawa.mantarobot.commands.currency.item.ItemStack;
import net.kodehawa.mantarobot.commands.currency.item.PlayerEquipment;
import net.kodehawa.mantarobot.commands.currency.item.special.helpers.Breakable;
import net.kodehawa.mantarobot.commands.currency.profile.Badge;
import net.kodehawa.mantarobot.commands.currency.profile.ProfileComponent;
import net.kodehawa.mantarobot.commands.currency.profile.StatsComponent;
import net.kodehawa.mantarobot.commands.currency.profile.inventory.InventorySortType;
import net.kodehawa.mantarobot.core.CommandRegistry;
import net.kodehawa.mantarobot.core.command.meta.*;
import net.kodehawa.mantarobot.core.command.slash.*;
import net.kodehawa.mantarobot.core.listeners.operations.ModalOperations;
import net.kodehawa.mantarobot.core.listeners.operations.core.ModalOperation;
import net.kodehawa.mantarobot.core.listeners.operations.core.Operation;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.modules.commands.base.CommandCategory;
import net.kodehawa.mantarobot.core.modules.commands.i18n.I18nContext;
import net.kodehawa.mantarobot.data.I18n;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.utils.Snow64;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.commands.DiscordUtils;
import net.kodehawa.mantarobot.utils.commands.EmoteReference;
import net.kodehawa.mantarobot.utils.commands.ratelimit.IncreasingRateLimiter;
import net.kodehawa.mantarobot.utils.commands.ratelimit.RatelimitUtils;

import java.awt.*;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static net.kodehawa.mantarobot.commands.currency.profile.ProfileComponent.*;
import static net.kodehawa.mantarobot.utils.Utils.createLinkedList;

@Module
public class ProfileCmd {
    private static final Pattern offsetRegex = Pattern.compile("(?:UTC|GMT)[+-][0-9]{1,2}(:[0-9]{1,2})?", Pattern.CASE_INSENSITIVE);

    // Discord makes it so multiple spaces get only rendered as one, but half-width spaces don't.
    // So therefore you get this cursed *thing* for formatting.
    private static final String SEPARATOR = "\u2009\u2009\u2009\u2009\u2009\u2009\u2009\u2009";
    private static final String SEPARATOR_ONE = "\u2009\u2009";
    private static final String SEPARATOR_HALF = "\u2009";

    // A small white square.
    private static final String LIST_MARKER = "\u25AB\uFE0F";
    private static final List<ProfileComponent> defaultOrder;
    private static final List<ProfileComponent> noOldOrder = createLinkedList(HEADER, CREDITS, LEVEL, REPUTATION, BIRTHDAY, MARRIAGE, INVENTORY, BADGES, PET);
    private static final IncreasingRateLimiter profileRatelimiter = new IncreasingRateLimiter.Builder()
            .limit(2) //twice every 10m
            .spamTolerance(2)
            .cooldown(10, TimeUnit.MINUTES)
            .cooldownPenaltyIncrease(10, TimeUnit.SECONDS)
            .maxCooldown(15, TimeUnit.MINUTES)
            .pool(MantaroData.getDefaultJedisPool())
            .prefix("profile")
            .build();

    static {
        final var config = MantaroData.config().get();
        if (config.isPremiumBot() || config.isSelfHost()) {
            defaultOrder = createLinkedList(HEADER, CREDITS, LEVEL, REPUTATION, BIRTHDAY, MARRIAGE, INVENTORY, BADGES, PET);
        } else {
            defaultOrder = createLinkedList(HEADER, CREDITS, OLD_CREDITS, LEVEL, REPUTATION, BIRTHDAY, MARRIAGE, INVENTORY, BADGES, PET);
        }
    }

    @Subscribe
    public void register(CommandRegistry cr) {
        cr.registerSlash(Profile.class);
        cr.registerContextUser(ProfileContext.class);
    }

    @Name("profile")
    @Description("The hub for profile-related operations.")
    @Category(CommandCategory.CURRENCY)
    @Help(description = " The hub for profile-related operations.",
            usage = """
                    To show your profile use `/profile show`.
                    *The profile command only shows the 5 most important badges*. To get a full list use `/badges`.
                    """)
    public static class Profile extends SlashCommand {
        @Override
        protected void process(SlashContext ctx) {}

        @Description("Shows your current profile.")
        @Options({@Options.Option(type = OptionType.USER, name = "user", description = "The user to see the profile of.")})
        @Help(
                description = "See your profile, or someone else's profile.",
                usage = "`/profile show user:[user]`",
                parameters = {@Help.Parameter(name = "user", description = "The user to see the profile of.", optional = true)}
        )

        public static class Show extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                final var userLooked = ctx.getOptionAsUser("user", ctx.getAuthor());

                if (userLooked.isBot()) {
                    ctx.reply("commands.profile.bot_notice", EmoteReference.ERROR);
                    return;
                }

                ctx.send(buildProfile(ctx, userLooked));
            }
        }

        @Description("Toggles the ability to do action commands to you.")
        @Ephemeral
        public static class ToggleAction extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                final var dbUser = ctx.getDBUser();
                final var userData = dbUser.getData();
                final var isDisabled = userData.isActionsDisabled();

                if (isDisabled) {
                    userData.setActionsDisabled(false);
                    ctx.reply("commands.profile.toggleaction.enabled", EmoteReference.CORRECT);
                } else {
                    userData.setActionsDisabled(true);
                    ctx.reply("commands.profile.toggleaction.disabled", EmoteReference.CORRECT);
                }

                dbUser.save();
            }
        }

        @Description("Locks you from being claimed. Use remove to remove it.")
        @Ephemeral
        @Options({@Options.Option(type = OptionType.BOOLEAN, name = "remove", description = "Remove claimlock.")})
        public static class ClaimLock extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                final var player = ctx.getPlayer();
                final var playerData = player.getData();

                if (ctx.getOptionAsBoolean("remove")) {
                    playerData.setClaimLocked(false);
                    ctx.reply("commands.profile.claimlock.removed", EmoteReference.CORRECT);
                    player.saveUpdating();
                    return;
                }

                if (playerData.isClaimLocked()) {
                    ctx.reply("commands.profile.claimlock.already_locked", EmoteReference.CORRECT);
                    return;
                }

                var inventory = player.getInventory();
                if (!inventory.containsItem(ItemReference.CLAIM_KEY)) {
                    ctx.reply("commands.profile.claimlock.no_key", EmoteReference.ERROR);
                    return;
                }

                playerData.setClaimLocked(true);
                ctx.reply("commands.profile.claimlock.success", EmoteReference.CORRECT);
                inventory.process(new ItemStack(ItemReference.CLAIM_KEY, -1));
                player.saveUpdating();
            }
        }

        @Description("Toggles the display of legacy credits.")
        @Ephemeral
        public static class ToggleLegacy extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                final var player = ctx.getPlayer();
                final var data = player.getData();
                var toSet = !data.isHiddenLegacy();
                data.setHiddenLegacy(toSet);

                player.saveUpdating();
                ctx.reply("commands.profile.hidelegacy", EmoteReference.CORRECT, data.isHiddenLegacy());
            }
        }

        @Description("Sort your inventory.")
        @Ephemeral
        @Options({@Options.Option(
                type = OptionType.STRING,
                name = "type",
                description = "The sort type. If nothing is specified, it prints a list.",
                choices = {
                        @Options.Choice(description = "Sort by individual value", value = "value"),
                        @Options.Choice(description = "Sort by amount", value = "amount"),
                        @Options.Choice(description = "Sort by type", value = "type"),
                        @Options.Choice(description = "Sort by total value", value = "value_total"),
                        @Options.Choice(description = "Sort randomly", value = "random"),
                }
        )})
        @Help(
                description = "Lets you sort your inventory using specified presets.",
                usage = "`/profile sort type:[preset]`",
                parameters = {
                        @Help.Parameter(name = "type", description = "The sort type to use.")
                }
        )
        public static class InventorySort extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var typeString = ctx.getOptionAsString("sort");
                final var type = Utils.lookupEnumString(typeString, InventorySortType.class);

                if (type == null) {
                    ctx.reply("commands.profile.inventorysort.not_valid",
                            EmoteReference.ERROR, Arrays.stream(InventorySortType.values())
                                    .map(b1 -> b1.toString().toLowerCase())
                                    .collect(Collectors.joining(", "))
                    );
                    return;
                }

                final var player = ctx.getPlayer();
                final var playerData = player.getData();

                playerData.setInventorySortType(type);
                player.saveUpdating();

                ctx.reply("commands.profile.inventorysort.success", EmoteReference.CORRECT, type.toString().toLowerCase());
            }
        }

        @Description("Toggles auto-equipping a new tool on break. Use disable to disable it.")
        @Ephemeral
        @Options({@Options.Option(type = OptionType.BOOLEAN, name = "disable", description = "Disable autoequip.")})
        @Help(
                description = "Enables auto equip, or disables it if specified.",
                usage = "`/profile autoequip disable:[true/false]`",
                parameters = {@Help.Parameter(name = "disable", description = "Whether to disable it.", optional = true)}
        )
        public static class AutoEquip extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var user = ctx.getDBUser();
                var data = user.getData();

                if (ctx.getOptionAsBoolean("disable")) {
                    data.setAutoEquip(false);
                    ctx.reply("commands.profile.autoequip.disable", EmoteReference.CORRECT);
                    user.saveUpdating();
                    return;
                }

                data.setAutoEquip(true);
                ctx.reply("commands.profile.autoequip.success", EmoteReference.CORRECT);
                user.saveUpdating();
            }
        }

        @Description("Hide or show the member id/tag from profile/waifu ls.")
        @Ephemeral
        public static class HideTag extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var user = ctx.getDBUser();
                var data = user.getData();

                data.setPrivateTag(!data.isPrivateTag());
                user.saveUpdating();

                ctx.reply("commands.profile.hide_tag.success", EmoteReference.POPPER, data.isPrivateTag());
            }
        }

        @Description("Sets your profile timezone.")
        @Ephemeral
        @Options({@Options.Option(type = OptionType.STRING, name = "timezone", description = "The timezone to use.", required = true)})
        @Help(
                description = "Sets your profile timezone.",
                usage = "`/profile timezone timezone:[zone]` - You can look up your timezone by googling what is my timezone.",
                parameters = {@Help.Parameter(name = "timezone", description = "The timezone to use.")}
        )
        public static class Timezone extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var dbUser = ctx.getDBUser();
                var timezone = ctx.getOptionAsString("timezone");
                if (offsetRegex.matcher(timezone).matches()) {
                    timezone = timezone.toUpperCase().replace("UTC", "GMT");
                }

                // EST, EDT, etc...
                if (timezone.length() == 3) {
                    timezone = timezone.toUpperCase();
                }

                if (timezone.equalsIgnoreCase("reset")) {
                    dbUser.getData().setTimezone(null);
                    dbUser.saveAsync();
                    ctx.reply("commands.profile.timezone.reset_success", EmoteReference.CORRECT);
                    return;
                }

                if (!Utils.isValidTimeZone(timezone)) {
                    ctx.reply("commands.profile.timezone.invalid", EmoteReference.ERROR);
                    return;
                }

                try {
                    Utils.formatDate(LocalDateTime.now(Utils.timezoneToZoneID(timezone)), dbUser.getData().getLang());
                } catch (DateTimeException e) {
                    ctx.reply("commands.profile.timezone.invalid", EmoteReference.ERROR);
                    return;
                }

                var player = ctx.getPlayer();
                if (player.getData().addBadgeIfAbsent(Badge.CALENDAR)) {
                    player.saveUpdating();
                }

                dbUser.getData().setTimezone(timezone);
                dbUser.saveUpdating();
                ctx.reply("commands.profile.timezone.success", EmoteReference.CORRECT, timezone);
            }
        }

        @Description("Sets your display badge.")
        @Ephemeral
        @Options({@Options.Option(type = OptionType.STRING, name = "badge", description = "The badge to display, reset/none to reset it or no badge. If nothing is specified, it prints a list.")})
        @Help(
                description = "Sets your profile display badge.",
                usage = "`/profile displaybadge badge:[badge name]` - Use reset to reset the badge to the default one and use none to show no badge.",
                parameters = {@Help.Parameter(name = "badge", description = "The badge to use.")}
        )
        public static class DisplayBadge extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var player = ctx.getPlayer();
                var data = player.getData();
                var badgeString = ctx.getOptionAsString("badge");
                if (badgeString.equalsIgnoreCase("none")) {
                    data.setShowBadge(false);
                    ctx.reply("commands.profile.displaybadge.reset_success", EmoteReference.CORRECT);
                    player.saveUpdating();
                    return;
                }

                if (badgeString.equalsIgnoreCase("reset")) {
                    data.setMainBadge(null);
                    data.setShowBadge(true);
                    ctx.reply("commands.profile.displaybadge.important_success", EmoteReference.CORRECT);
                    player.saveUpdating();
                    return;
                }

                var badge = Badge.lookupFromString(badgeString);
                if (badge == null) {
                    ctx.reply("commands.profile.displaybadge.no_such_badge", EmoteReference.ERROR,
                            player.getData().getBadges().stream()
                                    .map(Badge::getDisplay)
                                    .collect(Collectors.joining(", "))
                    );

                    return;
                }

                if (!data.getBadges().contains(badge)) {
                    ctx.reply("commands.profile.displaybadge.player_missing_badge", EmoteReference.ERROR,
                            player.getData().getBadges().stream()
                                    .map(Badge::getDisplay)
                                    .collect(Collectors.joining(", "))
                    );

                    return;
                }

                data.setShowBadge(true);
                data.setMainBadge(badge);
                player.saveUpdating();
                ctx.reply("commands.profile.displaybadge.success", EmoteReference.CORRECT, badge.display);
            }
        }

        @Description("Sets your profile language.")
        @Ephemeral
        @Options({@Options.Option(type = OptionType.STRING, name = "lang", description = "The language to use. See /mantaro language for a list.", required = true)})
        @Help(
                description = "Sets your profile language.",
                usage = "`/profile language lang:[lang code]`",
                parameters = {@Help.Parameter(name = "lang", description = "The language to use. See /mantaro language for a list.")}
        )
        public static class Language extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var dbUser = ctx.getDBUser();
                var content = ctx.getOptionAsString("lang");
                if (content.equalsIgnoreCase("reset")) {
                    dbUser.getData().setLang(null);
                    dbUser.saveUpdating();
                    ctx.reply("commands.profile.lang.reset_success", EmoteReference.CORRECT);
                    return;
                }

                if (I18n.isValidLanguage(content)) {
                    dbUser.getData().setLang(content);
                    //Create new I18n context based on the new language choice.
                    var newContext = new I18nContext(ctx.getDBGuild().getData(), dbUser.getData());

                    dbUser.saveUpdating();
                    ctx.reply(newContext.get("commands.profile.lang.success"), EmoteReference.CORRECT, content);
                } else {
                    ctx.reply("commands.profile.lang.invalid", EmoteReference.ERROR);
                }
            }
        }

        @Name("description")
        @Description("Sets your profile description. This will open a modal (pop-up).")
        @NoDefer
        @Options({@Options.Option(type = OptionType.BOOLEAN, name = "clear", description = "Clear your profile description if set to true.")})
        @Help(
                description = "Sets your profile description. The max length is 300 if you're not premium and 500 if you are. The pop-up will time out in 3 minutes.",
                usage = "`/profile description clear:[true/false]`",
                parameters = {@Help.Parameter(name = "clear", description = "TClear your profile description if set to true.")}
        )
        public static class DescriptionCommand extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                if (!RatelimitUtils.ratelimit(profileRatelimiter, ctx)) {
                    return;
                }

                if (ctx.getOptionAsBoolean("clear")) {
                    var player = ctx.getPlayer();
                    player.getData().setDescription(null);
                    ctx.reply("commands.profile.description.clear_success", EmoteReference.CORRECT);
                    player.saveUpdating();
                    return;
                }

                var dbUser = ctx.getDBUser();
                var MAX_LENGTH = 300;
                if (dbUser.isPremium()) {
                    MAX_LENGTH = 500;
                }

                var lang = ctx.getLanguageContext();
                var subject = TextInput.create("description", lang.get("commands.profile.description.header"), TextInputStyle.PARAGRAPH)
                        .setPlaceholder(lang.get("commands.profile.description.content_placeholder"))
                        .setRequiredRange(5, MAX_LENGTH)
                        .build();

                var id = "%s/%s/%s".formatted(Snow64.toSnow64(System.currentTimeMillis()), ctx.getAuthor().getId(), ctx.getChannel().getId());
                var modal = Modal.create(id, lang.get("commands.profile.description.header")).addActionRows(ActionRow.of(subject)).build();
                ctx.replyModal(modal);

                // effectively final moment
                var finalLength = MAX_LENGTH;
                ModalOperations.create(id, 180, new ModalOperation() {
                    @Override
                    public int modal(ModalInteractionEvent event) {
                        // This might not be possible here, as we send only events based on the id.
                        if (!event.getModalId().equalsIgnoreCase(id)) {
                            return Operation.IGNORED;
                        }

                        if (event.getUser().getIdLong() != ctx.getAuthor().getIdLong()) {
                            return Operation.IGNORED;
                        }

                        var description = event.getValue("description");
                        if (description == null) {
                            event.reply(lang.get("commands.profile.description.no_content").formatted(EmoteReference.ERROR))
                                    .setEphemeral(true)
                                    .queue();
                            return Operation.COMPLETED;
                        }

                        var desc = description.getAsString();
                        if (desc.isBlank()) {
                            event.reply(lang.get("commands.profile.description.no_content").formatted(EmoteReference.ERROR))
                                    .setEphemeral(true)
                                    .queue();
                            return Operation.COMPLETED;
                        }

                        if (desc.length() > finalLength) {
                            event.reply(lang.get("commands.profile.description.too_long").formatted(EmoteReference.ERROR))
                                    .setEphemeral(true)
                                    .queue();
                            return Operation.COMPLETED;
                        }

                        desc = Utils.DISCORD_INVITE.matcher(desc).replaceAll("-discord invite link-");
                        desc = Utils.DISCORD_INVITE_2.matcher(desc).replaceAll("-discord invite link-");

                        var player = ctx.getPlayer();
                        player.getData().setDescription(desc);
                        event.reply(lang.get("commands.profile.description.success").formatted(EmoteReference.POPPER))
                                .setEphemeral(true)
                                .queue();

                        player.getData().addBadgeIfAbsent(Badge.WRITER);
                        player.saveUpdating();
                        return Operation.COMPLETED;
                    }

                    @Override
                    public void onExpire() {
                        ModalOperation.super.onExpire();
                        ctx.getEvent().getHook()
                                .sendMessage(lang.get("commands.profile.description.time_out").formatted(EmoteReference.ERROR2))
                                .setEphemeral(true)
                                .queue();
                    }
                });

            }
        }

        @Description("See profile statistics.")
        @Options({@Options.Option(type = OptionType.USER, name = "user", description = "The user to see stats for.")})
        @Help(
                description = "See your profile stats, or someone else's profile stats.",
                usage = "`/profile stats user:[user]`",
                parameters = {@Help.Parameter(name = "user", description = "The user to see the stats of.", optional = true)}
        )
        public static class Stats extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var toLookup = ctx.getOptionAsUser("user", ctx.getAuthor());
                var lang = ctx.getLanguageContext();
                if (toLookup.isBot()) {
                    ctx.reply("commands.profile.bot_notice", EmoteReference.ERROR);
                    return;
                }

                var player = ctx.getPlayer(toLookup);
                var dbUser = ctx.getDBUser(toLookup);

                List<MessageEmbed.Field> fields = new LinkedList<>();
                for (StatsComponent component : StatsComponent.values()) {
                    fields.add(new MessageEmbed.Field(component.getEmoji() + component.getName(ctx),
                            component.getContent(new StatsComponent.Holder(ctx, lang, player, dbUser, toLookup)),
                            false)
                    );
                }

                var splitFields = DiscordUtils.divideFields(9, fields);
                var embed = new EmbedBuilder()
                        .setThumbnail(toLookup.getEffectiveAvatarUrl())
                        .setAuthor(lang.get("commands.profile.stats.header").formatted(toLookup.getName()),
                                null, toLookup.getEffectiveAvatarUrl()
                        )
                        .setDescription(String.format(lang.get("general.buy_sell_paged_react"), String.format(lang.get("general.reaction_timeout"), 200)))
                        .setColor(ctx.getMemberColor())
                        .setFooter("Thanks for using Mantaro! %s".formatted(EmoteReference.HEART), ctx.getGuild().getIconUrl());

                DiscordUtils.listButtons(ctx.getUtilsContext(), 200, embed, splitFields);
            }
        }

        @Description("Set the profile widget order. This is premium-only.")
        @Options({@Options.Option(type = OptionType.STRING, name = "order", description = "The widget order. Use reset to reset them. If nothing is specified, it prints a list.")})
        public static class Widgets extends SlashCommand {
            @Override
            protected void process(SlashContext ctx) {
                var user = ctx.getDBUser();
                var lang = ctx.getLanguageContext();

                if (!user.isPremium()) {
                    ctx.reply("commands.profile.display.not_premium", EmoteReference.ERROR);
                    return;
                }

                var player = ctx.getPlayer();
                var playerData = player.getData();
                var content = ctx.getOptionAsString("order");

                if (content == null) {
                    ctx.reply(
                            lang.get("commands.profile.display.ls") +
                                    lang.get("commands.profile.display.example"),
                            EmoteReference.ZAP, EmoteReference.BLUE_SMALL_MARKER,
                            defaultOrder.stream().map(Enum::name).collect(Collectors.joining(", ")),
                            playerData.getProfileComponents().size() == 0 ?
                                    "Not personalized" :
                                    playerData.getProfileComponents().stream()
                                            .map(Enum::name)
                                            .collect(Collectors.joining(", "))
                    );
                    return;
                }

                if (content.equalsIgnoreCase("reset")) {
                    playerData.getProfileComponents().clear();
                    player.save();

                    ctx.reply("commands.profile.display.reset", EmoteReference.CORRECT);
                    return;
                }

                var splitContent = content.replace(",", "").split("\\s+");
                List<ProfileComponent> newComponents = new LinkedList<>(); //new list of profile components

                for (var cmt : splitContent) {
                    var component = ProfileComponent.lookupFromString(cmt);
                    if (component != null && component.isAssignable()) {
                        newComponents.add(component);
                    }
                }

                if (newComponents.size() < 3) {
                    ctx.reply(lang.get("commands.profile.display.not_enough") +
                            lang.get("commands.profile.display.example"), EmoteReference.WARNING
                    );

                    return;
                }

                playerData.setProfileComponents(newComponents);
                player.save();

                ctx.reply("commands.profile.display.success",
                        EmoteReference.CORRECT, newComponents.stream().map(Enum::name).collect(Collectors.joining(", "))
                );
            }
        }
    }

    @Name("Show currency profile")
    @Ephemeral
    public static class ProfileContext extends ContextCommand<User> {
        @Override
        protected void process(InteractionContext<User> ctx) {
            var userLooked = ctx.getTarget();
            if (userLooked.isBot()) {
                ctx.reply("commands.profile.bot_notice", EmoteReference.ERROR);
                return;
            }

            // Could this even happen?
            if (ctx.getGuild().getMember(userLooked) == null) {
                ctx.sendLocalized("general.slash_member_lookup_failure", EmoteReference.ERROR);
                return;
            }

            ctx.send(buildProfile(ctx, userLooked));
        }
    }

    private static MessageEmbed buildProfile(IContext ctx, User userLooked) {
        final var memberLooked = ctx.getGuild().getMember(userLooked);
        final var player = ctx.getPlayer(userLooked);
        final var dbUser = ctx.getDBUser(userLooked);
        final var playerData = player.getData();
        final var userData = dbUser.getData();
        final var inv = player.getInventory();
        final var config = MantaroData.config().get();

        // Cache waifu value.
        playerData.setWaifuCachedValue(WaifuCmd.calculateWaifuValue(player, userLooked).getFinalValue());

        // start of badge assigning
        final var mh = MantaroBot.getInstance().getShardManager().getGuildById("213468583252983809");
        Member mhMember = null;
        if (mh != null) {
            try {
                mhMember = mh.retrieveMemberById(userLooked.getId()).useCache(true).complete();
            } catch (ErrorResponseException ignored) { } // Expected UNKNOWN_MEMBER
        }

        Badge.assignBadges(player, player.getStats(), dbUser);
        var christmasBadgeAssign = inv.asList()
                .stream()
                .map(ItemStack::getItem)
                .anyMatch(it -> it.equals(ItemReference.CHRISTMAS_TREE_SPECIAL) || it.equals(ItemReference.BELL_SPECIAL));

        // Manual badges
        if (config.isOwner(userLooked)) {
            playerData.addBadgeIfAbsent(Badge.DEVELOPER);
        }

        if (christmasBadgeAssign) {
            playerData.addBadgeIfAbsent(Badge.CHRISTMAS);
        }

        // Requires a valid Member in Mantaro Hub.
        if (mhMember != null) {
            // Admin
            if (containsRole(mhMember, 315910951994130432L)) {
                playerData.addBadgeIfAbsent(Badge.COMMUNITY_ADMIN);
            }

            // Patron - Donator
            if (containsRole(mhMember, 290902183300431872L, 290257037072531466L)) {
                playerData.addBadgeIfAbsent(Badge.DONATOR_2);
            }

            // Translator
            if (containsRole(mhMember, 407156441812828162L)) {
                playerData.addBadgeIfAbsent(Badge.TRANSLATOR);
            }
        }
        // end of badge assigning

        final var lang = ctx.getLanguageContext();
        final var badges = playerData.getBadges();
        Collections.sort(badges);

        final var marriage = MantaroData.db().getMarriage(userData.getMarriageId());
        final var ringHolder = player.getInventory().containsItem(ItemReference.RING) && marriage != null;
        final var holder = new ProfileComponent.Holder(userLooked, player, dbUser, marriage, badges);
        final var profileBuilder = new EmbedBuilder();
        var description = lang.get("commands.profile.no_desc");

        if (playerData.getDescription() != null) {
            description = player.getData().getDescription();
        }

        profileBuilder.setAuthor(
                        (ringHolder ? EmoteReference.RING : "") + String.format(lang.get("commands.profile.header"),
                                userLooked.getName()), null, userLooked.getEffectiveAvatarUrl())
                .setDescription(description)
                .setThumbnail(userLooked.getEffectiveAvatarUrl())
                .setColor(memberLooked.getColor() == null ? Color.PINK : memberLooked.getColor())
                .setFooter(ProfileComponent.FOOTER.getContent().apply(holder, lang),
                        ctx.getAuthor().getEffectiveAvatarUrl()
                );

        var hasCustomOrder = dbUser.isPremium() && !playerData.getProfileComponents().isEmpty();
        var usedOrder = hasCustomOrder ? playerData.getProfileComponents() : defaultOrder;
        if ((!config.isPremiumBot() && player.getOldMoney() < 5000 && !hasCustomOrder) ||
                (playerData.isHiddenLegacy() && !hasCustomOrder)) {
            usedOrder = noOldOrder;
        }

        for (var component : usedOrder) {
            profileBuilder.addField(
                    component.getTitle(lang), component.getContent().apply(holder, lang), component.isInline()
            );
        }

        // We don't need to update stats if someone else views your profile
        if (player.getUserId().equals(ctx.getAuthor().getId())) {
            player.saveUpdating();
        }

        return profileBuilder.build();
    }

    public static String parsePlayerEquipment(PlayerEquipment equipment, I18nContext languageContext) {
        var toolsEquipment = equipment.getEquipment();
        var separator = SEPARATOR + SEPARATOR_HALF + LIST_MARKER + SEPARATOR_HALF;

        if (toolsEquipment.isEmpty()) {
            return separator + languageContext.get("general.none");
        }

        return toolsEquipment.entrySet().stream().map((entry) -> {
            var item = ItemHelper.fromId(entry.getValue());

            return Utils.capitalize(entry.getKey().toString()) + ": " + SEPARATOR_ONE +
                    item.toDisplayString() + SEPARATOR_HALF + " [%,d / %,d]"
                    .formatted(equipment.getDurability().get(entry.getKey()), ((Breakable) item).getMaxDurability());
        }).collect(Collectors.joining("\n"));
    }

    private static boolean containsRole(Member member, long... roles) {
        return member.getRoles().stream().map(Role::getIdLong).anyMatch(id -> Arrays.stream(roles).anyMatch(i -> i == id));
    }
}

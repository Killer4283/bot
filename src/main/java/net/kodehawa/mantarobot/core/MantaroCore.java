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

package net.kodehawa.mantarobot.core;

import com.google.common.eventbus.EventBus;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import io.github.classgraph.ClassGraph;
import io.github.classgraph.ClassInfo;
import io.github.classgraph.ScanResult;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.GenericEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.exceptions.InvalidTokenException;
import net.dv8tion.jda.api.hooks.EventListener;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.sharding.DefaultShardManagerBuilder;
import net.dv8tion.jda.api.sharding.ShardManager;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.SessionController;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import net.dv8tion.jda.api.utils.messages.MessageRequest;
import net.kodehawa.mantarobot.ExtraRuntimeOptions;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.commands.music.listener.VoiceChannelListener;
import net.kodehawa.mantarobot.core.cache.EvictingCachePolicy;
import net.kodehawa.mantarobot.core.command.processor.CommandProcessor;
import net.kodehawa.mantarobot.core.listeners.MantaroListener;
import net.kodehawa.mantarobot.core.listeners.command.CommandListener;
import net.kodehawa.mantarobot.core.listeners.events.PostLoadEvent;
import net.kodehawa.mantarobot.core.listeners.events.PreLoadEvent;
import net.kodehawa.mantarobot.core.listeners.operations.ButtonOperations;
import net.kodehawa.mantarobot.core.listeners.operations.InteractiveOperations;
import net.kodehawa.mantarobot.core.listeners.operations.ModalOperations;
import net.kodehawa.mantarobot.core.listeners.operations.ReactionOperations;
import net.kodehawa.mantarobot.core.modules.Module;
import net.kodehawa.mantarobot.core.shard.Shard;
import net.kodehawa.mantarobot.core.shard.discord.BotGateway;
import net.kodehawa.mantarobot.core.shard.jda.BucketedController;
import net.kodehawa.mantarobot.data.Config;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.options.annotations.Option;
import net.kodehawa.mantarobot.options.event.OptionRegistryEvent;
import net.kodehawa.mantarobot.utils.Utils;
import net.kodehawa.mantarobot.utils.data.JsonDataManager;
import net.kodehawa.mantarobot.utils.exporters.Metrics;
import net.kodehawa.mantarobot.utils.external.BotListPost;
import net.kodehawa.mantarobot.utils.log.LogUtils;
import okhttp3.Request;
import okhttp3.Response;
import org.apache.commons.collections4.ListUtils;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static net.kodehawa.mantarobot.core.LoadState.LOADED;
import static net.kodehawa.mantarobot.core.LoadState.LOADING;
import static net.kodehawa.mantarobot.core.LoadState.POSTLOAD;
import static net.kodehawa.mantarobot.core.LoadState.PRELOAD;
import static net.kodehawa.mantarobot.core.cache.EvictionStrategy.leastRecentlyUsed;
import static net.kodehawa.mantarobot.utils.ShutdownCodes.SHARD_FETCH_FAILURE;

public class MantaroCore {
    private static final Logger log = LoggerFactory.getLogger(MantaroCore.class);
    private static final VoiceChannelListener VOICE_CHANNEL_LISTENER = new VoiceChannelListener();

    private static LoadState loadState = PRELOAD;
    private final Map<Integer, Shard> shards = new ConcurrentHashMap<>();
    private final ExecutorService threadPool = Executors.newCachedThreadPool(
            new ThreadFactoryBuilder().setNameFormat("Mantaro Thread-%d").build()
    );
    private final Config config;
    private final boolean isDebug;
    private String commandsPackage;
    private String optsPackage;
    private final CommandProcessor commandProcessor = new CommandProcessor();
    private EventBus shardEventBus;
    private ShardManager shardManager;
    private int restPing;

    public MantaroCore(Config config, boolean isDebug) {
        this.config = config;
        this.isDebug = isDebug;
        Metrics.THREAD_POOL_COLLECTOR.add("mantaro-executor", threadPool);
    }

    public static boolean hasLoadedCompletely() {
        return getLoadState().equals(POSTLOAD);
    }

    public static LoadState getLoadState() {
        return loadState;
    }

    public static void setLoadState(LoadState loadState) {
        MantaroCore.loadState = loadState;
    }

    private static int getInstanceShards(String token) {
        if (ExtraRuntimeOptions.SHARD_SUBSET) {
            return ExtraRuntimeOptions.TO_SHARD.orElseThrow() - ExtraRuntimeOptions.FROM_SHARD.orElseThrow();
        }

        if (ExtraRuntimeOptions.SHARD_COUNT.isPresent()) {
            return ExtraRuntimeOptions.SHARD_COUNT.getAsInt();
        }

        try {
            var shards = new Request.Builder()
                    .url("https://discordapp.com/api/gateway/bot")
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .build();

            try (var response = Utils.httpClient.newCall(shards).execute()) {
                var body = response.body();

                if (body == null) {
                    throw new IllegalStateException("Error requesting shard count: " + response.code() + " " + response.message());
                }

                var shardObject = new JSONObject(body.string());
                return shardObject.getInt("shards");
            }

        } catch (Exception e) {
            log.error("Unable to fetch shard count", e);
            System.exit(SHARD_FETCH_FAILURE);
        }
        return 1;
    }

    public MantaroCore setOptionsPackage(String optionsPackage) {
        this.optsPackage = optionsPackage;
        return this;
    }

    public MantaroCore setCommandsPackage(String commandsPackage) {
        this.commandsPackage = commandsPackage;
        return this;
    }

    private void startShardedInstance() {
        loadState = LOADING;

        SessionController controller;
        if (isDebug) {
            // Bucketed controller still prioritizes home guild and reconnecting shards.
            // Only really useful in the node that actually contains the guild, but worth keeping.
            controller = new BucketedController(1, 213468583252983809L);
        } else {
            var bucketFactor = config.getBucketFactor();
            if (bucketFactor > 1) {
                log.info("Using buckets of {} shards to start the bot! Assuming we're on big bot sharding." , bucketFactor);
                log.info("If you're self-hosting, set bucketFactor in config.json to 1 and isSelfHost to true.");
            }

            controller = new BucketedController(bucketFactor, 213468583252983809L);
        }

        var gatewayThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("GatewayThread-%d")
                .setDaemon(true)
                .setPriority(Thread.MAX_PRIORITY)
                .build();
        var requesterThreadFactory = new ThreadFactoryBuilder()
                .setNameFormat("RequesterThread-%d")
                .setDaemon(true)
                .build();

        try {
            // Don't allow mentioning @everyone, @here or @role (can be overridden in a per-command context, but we only ever re-enable role)
            var deny = EnumSet.of(Message.MentionType.EVERYONE, Message.MentionType.HERE, Message.MentionType.ROLE);
            MessageRequest.setDefaultMentions(EnumSet.complementOf(deny));

            // Gateway Intents to enable.
            // We used to have GUILD_PRESENCES here for caching before, since chunking wasn't possible, but we needed to remove it.
            // So we have no permanent cache anymore.
            GatewayIntent[] toEnable = {
                    GatewayIntent.GUILD_MESSAGES, // Receive guild messages, needed to, well operate at all.
                    GatewayIntent.MESSAGE_CONTENT, // Receive message content.
                    GatewayIntent.GUILD_MESSAGE_REACTIONS,  // Receive message reactions, used for reaction menus.
                    GatewayIntent.GUILD_MEMBERS, // Receive member events, needed for mod features *and* welcome/leave messages.
            };

            // This is used so we can fire PostLoadEvent properly.
            var shardStartListener = new ShardStartListener();
            Object[] eventListeners;

            var enabled = new ArrayList<>(List.of(toEnable));
            EnumSet<CacheFlag> disabledIntents;
            if (config.musicEnable()) {
                disabledIntents = EnumSet.of(
                        CacheFlag.ACTIVITY, CacheFlag.EMOJI, CacheFlag.CLIENT_STATUS,
                        CacheFlag.ROLE_TAGS, CacheFlag.ONLINE_STATUS, CacheFlag.STICKER,
                        CacheFlag.SCHEDULED_EVENTS, CacheFlag.FORUM_TAGS
                );

                eventListeners = new Object[] {
                        VOICE_CHANNEL_LISTENER,
                        MantaroBot.getInstance().getLavaLink(),
                        InteractiveOperations.listener(),
                        ReactionOperations.listener(),
                        ButtonOperations.listener(),
                        ModalOperations.listener(),
                        shardStartListener
                };

                enabled.add(GatewayIntent.GUILD_VOICE_STATES); // Receive voice states, needed so Member#getVoiceState doesn't return null.
                log.info("Music has been enabled.");
            } else {
                disabledIntents = EnumSet.of(
                        CacheFlag.ACTIVITY, CacheFlag.EMOJI, CacheFlag.CLIENT_STATUS,
                        CacheFlag.ROLE_TAGS, CacheFlag.ONLINE_STATUS, CacheFlag.STICKER,
                        CacheFlag.SCHEDULED_EVENTS, CacheFlag.FORUM_TAGS, CacheFlag.VOICE_STATE
                );

                eventListeners = new Object[] {
                        InteractiveOperations.listener(),
                        ReactionOperations.listener(),
                        ButtonOperations.listener(),
                        ModalOperations.listener(),
                        shardStartListener
                };

                log.info("Music has been disabled.");
            }

            log.info("Using intents: {}", enabled.stream()
                    .map(Enum::name)
                    .collect(Collectors.joining(", "))
            );

            log.info("Disabled flags: {}", disabledIntents.stream()
                    .map(Enum::name)
                    .collect(Collectors.joining(", "))
            );

            var shardManager = DefaultShardManagerBuilder.create(config.token, enabled)
                    // Can't do chunking with Gateway Intents enabled, fun, but don't need it anymore.
                    .setChunkingFilter(ChunkingFilter.NONE)
                    .setSessionController(controller)
                    .addEventListeners(eventListeners)
                    .addEventListenerProviders(List.of(
                            id -> new CommandListener(commandProcessor, threadPool, getShard(id).getMessageCache()),
                            id -> new MantaroListener(threadPool, getShard(id).getMessageCache()),
                            id -> getShard(id).getListener()
                    ))
                    .setEventManagerProvider(id -> getShard(id).getManager())
                    // Don't spam on mass-prune.
                    .setBulkDeleteSplittingEnabled(false)
                    .disableCache(disabledIntents)
                    .setActivity(Activity.playing("Hold on to your seatbelts!"));

            if (config.musicEnable()) {
                shardManager.setVoiceDispatchInterceptor(MantaroBot.getInstance().getLavaLink().getVoiceInterceptor());
            }

            /* only create eviction strategies that will get used */
            List<Integer> shardIds;
            int latchCount;

            if (isDebug) {
                var shardCount = 2;
                shardIds = List.of(0, 1);
                latchCount = shardCount;
                shardManager.setShardsTotal(shardCount)
                        .setGatewayPool(Executors.newSingleThreadScheduledExecutor(gatewayThreadFactory), true)
                        .setRateLimitPool(Executors.newScheduledThreadPool(2, requesterThreadFactory), true);
                log.info("Debug instance, using {} shards", shardCount);
            } else {
                int shardCount;
                // Count specified in config.
                if (config.totalShards != 0) {
                    shardCount = config.totalShards;
                    shardManager.setShardsTotal(config.totalShards);
                    log.info("Using {} shards from config (totalShards != 0)", shardCount);
                } else {
                    //Count specified on runtime options or recommended count by Discord.
                    shardCount = ExtraRuntimeOptions.SHARD_COUNT.orElseGet(() -> getInstanceShards(config.token));
                    shardManager.setShardsTotal(shardCount);
                    if (ExtraRuntimeOptions.SHARD_COUNT.isPresent()) {
                        log.info("Using {} shards from ExtraRuntimeOptions", shardCount);
                    } else {
                        log.info("Using {} shards from discord recommended amount", shardCount);
                    }
                }

                // Using a shard subset. FROM_SHARD is inclusive, TO_SHARD is exclusive (else 0 to 448 would start 449 shards)
                if (ExtraRuntimeOptions.SHARD_SUBSET) {
                    if (ExtraRuntimeOptions.SHARD_SUBSET_MISSING) {
                        throw new IllegalStateException("Both mantaro.from-shard and mantaro.to-shard must be specified " +
                                "when using shard subsets. Please specify the missing one.");
                    }

                    var from = ExtraRuntimeOptions.FROM_SHARD.orElseThrow();
                    var to = ExtraRuntimeOptions.TO_SHARD.orElseThrow() - 1;
                    shardIds = IntStream.rangeClosed(from, to).boxed().collect(Collectors.toList());
                    latchCount = to - from + 1;

                    log.info("Using shard range {}-{}", from, to);
                    shardManager.setShards(from, to);
                } else {
                    shardIds = IntStream.range(0, shardCount).boxed().collect(Collectors.toList());
                    latchCount = shardCount;
                }
    
                var gatewayThreads = Math.max(1, latchCount / 16);
                var rateLimitThreads = Math.max(2, latchCount * 5 / 4);

                log.info("Gateway pool: {} threads", gatewayThreads);
                log.info("Rate limit pool: {} threads", rateLimitThreads);

                shardManager.setGatewayPool(Executors.newScheduledThreadPool(gatewayThreads, gatewayThreadFactory), true)
                        .setRateLimitPool(Executors.newScheduledThreadPool(rateLimitThreads, requesterThreadFactory), true);
            }

            // If this isn't true we have a big problem
            if (shardIds.size() != latchCount) {
                throw new IllegalStateException("Shard ids list must have the same size as latch count");
            }

            // Use a LRU cache policy.
            shardManager.setMemberCachePolicy(new EvictingCachePolicy(shardIds, () -> leastRecentlyUsed(config.memberCacheSize)));
            MantaroCore.setLoadState(LoadState.LOADING_SHARDS);

            log.info("Spawning {} shards...", latchCount);
            var start = System.currentTimeMillis();
            shardStartListener.setLatch(new CountDownLatch(latchCount));
            this.shardManager = shardManager.build();

            //This is so it doesn't block command registering, lol.
            threadPool.submit(() -> {
                log.info("CountdownLatch started: Awaiting for {} shards to be counted down to start PostLoad.", latchCount);

                try {
                    shardStartListener.latch.await();
                    var elapsed = System.currentTimeMillis() - start;

                    log.info("All shards logged in! Took {} seconds", TimeUnit.MILLISECONDS.toSeconds(elapsed));
                    this.shardManager.removeEventListener(shardStartListener);
                    startPostLoadProcedure(elapsed);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });
        } catch (InvalidTokenException e) {
            throw new IllegalStateException(e);
        }

        loadState = LOADED;
    }

    public void start() {
        if (config == null) {
            throw new IllegalArgumentException("Config cannot be null!");
        }

        if (commandsPackage == null) {
            throw new IllegalArgumentException("Cannot look for commands if you don't specify where!");
        }

        if (optsPackage == null) {
            throw new IllegalArgumentException("Cannot look for options if you don't specify where!");
        }

        var commands = lookForAnnotatedOn(commandsPackage, Module.class);
        var options = lookForAnnotatedOn(optsPackage, Option.class);

        shardEventBus = new EventBus();

        // Start the actual bot now.
        startShardedInstance();

        for (var commandClass : commands) {
            try {
                shardEventBus.register(commandClass.getDeclaredConstructor().newInstance());
            } catch (Exception e) {
                log.error("Invalid module: no zero arg public constructor found for " + commandClass);
            }
        }

        for (var optionClass : options) {
            try {
                shardEventBus.register(optionClass.getDeclaredConstructor().newInstance());
            } catch (Exception e) {
                log.error("Invalid module: no zero arg public constructor found for " + optionClass);
            }
        }

        new Thread(() -> {
            // For now, only used by AsyncInfoMonitor startup and Anime Login Task.
            shardEventBus.post(new PreLoadEvent());

            log.info("Registering all commands (@Module)");
            shardEventBus.post(CommandProcessor.REGISTRY);
            log.info("Registered all commands (@Module)");
            log.info("Registering all options (@Option)");
            shardEventBus.post(new OptionRegistryEvent());
            log.info("Registered all options (@Option)");
        }, "Mantaro EventBus-Post").start();
    }

    public void markAsReady() {
        loadState = POSTLOAD;
    }

    public ShardManager getShardManager() {
        return shardManager;
    }

    public Shard getShard(int id) {
        return shards.computeIfAbsent(id, Shard::new);
    }

    public Collection<Shard> getShards() {
        return Collections.unmodifiableCollection(shards.values());
    }

    public int getRestPing() {
        return restPing;
    }

    public void setRestPing(int restPing) {
        this.restPing = restPing;
    }

    public void registerSlash(List<CommandData> data) {
        if (MantaroBot.getInstance().isMasterNode()) {
            log.info("[Controller] Attempted to register Slash/Context commands (@Module). List size: {}", data.size());
            var jda = getShard(0).getJDA();
            jda.updateCommands().addCommands(data).queue();
        }
    }

    private Set<Class<?>> lookForAnnotatedOn(String packageName, Class<? extends Annotation> annotation) {
        var classGraph = new ClassGraph()
                .acceptPackages(packageName)
                .enableAnnotationInfo();

        try (ScanResult result = classGraph.scan(2)) {
            return result.getAllClasses()
                    .stream()
                    .filter(classInfo -> classInfo.hasAnnotation(annotation.getName())).map(ClassInfo::loadClass)
                    .collect(Collectors.toSet());
        }
    }

    public EventBus getShardEventBus() {
        return this.shardEventBus;
    }

    @SuppressWarnings("unused")
    public BotGateway getGateway(JDA jda) throws IOException {
        var call = Utils.httpClient.newCall(new Request.Builder()
                .url("https://discordapp.com/api/gateway/bot")
                .header("Authorization", jda.getToken())
                .build()
        );

        try (Response response = call.execute()) {
            final var body = response.body();
            if (body == null) {
                return null;
            }

            var json = body.string();
            return JsonDataManager.fromJson(json, BotGateway.class);
        }
    }

    private void startPostLoadProcedure(long elapsed) {
        var bot = MantaroBot.getInstance();

        // Start the reconnect queue.
        bot.getCore().markAsReady();

        // Get the amount of clusters
        int clusterTotal;
        try(var jedis = MantaroData.getDefaultJedisPool().getResource()) {
            var clusters = jedis.hgetAll("node-stats-" + config.getClientId());
            clusterTotal = clusters.size();
        }

        log.info("Not aware of anything holding off boot now, considering bot as started up");
        LogUtils.shard(
                """
                Loaded all %d shards and %d commands.
                Took %s to start this node (%d). Total nodes: %d.
                Cross-node shard count is %d.""".formatted(
                        shardManager.getShardsRunning(), CommandProcessor.REGISTRY.commands().size(),
                        Utils.formatDuration(null, elapsed), bot.getNodeNumber(), clusterTotal,
                        shardManager.getShardsTotal()
                )
        );

        log.info("Loaded all shards successfully! Current status: {}", MantaroCore.getLoadState());

        log.info("Firing PostLoadEvent...");
        bot.getCore().getShardEventBus().post(new PostLoadEvent());

        // Only update guild count from the master node.
        // Might not want to run this if it's self-hosted either.
        if (bot.isMasterNode() && !config.isSelfHost()) {
            startUpdaters();
        }

        bot.startCheckingBirthdays();
        startMonitor();
        var slashList = CommandProcessor.REGISTRY.getCommandManager().getSlashCommandsList();
        var userContextList = CommandProcessor.REGISTRY.getCommandManager().getContextUserCommandsList();
        var union = ListUtils.union(slashList, userContextList);
        registerSlash(union);

        if (bot.isMasterNode()) {
            LogUtils.log(
                    """
                    Loaded all slash commands. Current count is %,d (Slash: %,d, Context: %,d)"""
                            .formatted(union.size(), slashList.size(), userContextList.size())
            );
        }
    }

    private void startUpdaters() {
        log.info("Starting bot list count executor...");
        Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Mantaro Server Count Updater")).scheduleAtFixedRate(() -> {
            try {
                var serverCount = 0L;
                //Fetch actual guild count.
                try(var jedis = MantaroData.getDefaultJedisPool().getResource()) {
                    var stats = jedis.hgetAll("shardstats-" + config.getClientId());

                    for (var shards : stats.entrySet()) {
                        var json = new JSONObject(shards.getValue());
                        serverCount += json.getLong("guild_count");
                    }
                }

                // This will NOP if the token is null.
                for(var listSites : BotListPost.values()) {
                    listSites.createRequest(serverCount, config.clientId);
                }

                log.debug("Updated server count ({}) for all bot lists", serverCount);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }, 0, 10, TimeUnit.MINUTES);
    }

    private void startMonitor() {
        log.info("Starting latency monitor...");
        Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "Mantaro Latency Monitor")).scheduleAtFixedRate(() -> {
            try {
                var shard = 0;
                if (ExtraRuntimeOptions.FROM_SHARD.isPresent()) {
                    shard = ExtraRuntimeOptions.FROM_SHARD.getAsInt() + 1;
                }

                var previous = getRestPing();
                var value = shards.get(shard).getJDA().getRestPing().complete().intValue();
                if (previous < 625 && value > 625) {
                    log.warn("Detected lag! This will defer Slash Commands until it's resolved.");
                } else if (previous > 625 && value < 625) {
                    log.warn("Disabled Slash Command auto-defer.");
                }

                setRestPing(value);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, 0, 30, TimeUnit.SECONDS);
    }

    private static class ShardStartListener implements EventListener {
        private CountDownLatch latch;

        public void setLatch(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void onEvent(@Nonnull GenericEvent event) {
            if (event instanceof ReadyEvent) {
                var sm = event.getJDA().getShardManager();
                if (sm == null) { // We have a big problem if this happens.
                    throw new AssertionError();
                }

                latch.countDown();
            }
        }
    }
}

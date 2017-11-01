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

package net.kodehawa.mantarobot.core.shard;

import br.com.brjdevs.java.utils.async.Async;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.kodehawa.mantarobot.MantaroBot;
import net.kodehawa.mantarobot.core.LoadState;
import net.kodehawa.mantarobot.core.MantaroCore;
import net.kodehawa.mantarobot.core.MantaroEventManager;
import net.kodehawa.mantarobot.core.listeners.events.PostLoadEvent;
import net.kodehawa.mantarobot.core.processor.core.ICommandProcessor;
import net.kodehawa.mantarobot.core.shard.watcher.ShardWatcher;
import net.kodehawa.mantarobot.data.MantaroData;
import net.kodehawa.mantarobot.log.LogUtils;
import net.kodehawa.mantarobot.services.Carbonitex;
import net.kodehawa.mantarobot.utils.SentryHelper;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static net.kodehawa.mantarobot.utils.ShutdownCodes.SHARD_FETCH_FAILURE;

@Slf4j
public class ShardedMantaro {

    @Getter
    private final List<MantaroEventManager> managers = new ArrayList<>();
    private final ICommandProcessor processor;
    @Getter
    private final MantaroShard[] shards;
    @Getter
    private final int totalShards;
    private final Carbonitex carbonitex = new Carbonitex();


    public ShardedMantaro(int totalShards, boolean isDebug, boolean auto, String token, ICommandProcessor commandProcessor) {
        int shardAmount = totalShards;
        if(auto) shardAmount = getRecommendedShards(token);
        if(isDebug) shardAmount = 2;
        this.totalShards = shardAmount;
        processor = commandProcessor;
        shards = new MantaroShard[this.totalShards];
    }

    private static int getRecommendedShards(String token) {
        if(MantaroData.config().get().totalShards != 0) {
            return MantaroData.config().get().totalShards;
        }

        try {
            OkHttpClient okHttp = new OkHttpClient();
            Request shards = new Request.Builder()
                    .url("https://discordapp.com/api/gateway/bot")
                    .header("Authorization", "Bot " + token)
                    .header("Content-Type", "application/json")
                    .build();

            Response response = okHttp.newCall(shards).execute();
            JSONObject shardObject = new JSONObject(response.body().string());
            response.close();
            return shardObject.getInt("shards");
        } catch(Exception e) {
            SentryHelper.captureExceptionContext(
                    "Exception thrown when trying to get shard count, discord isn't responding?", e, MantaroBot.class, "Shard Count Fetcher"
            );
            System.exit(SHARD_FETCH_FAILURE);
        }
        return 1;
    }

    public void shard() {
        try {
            MantaroCore.setLoadState(LoadState.LOADING_SHARDS);
            log.info("Spawning shards...");
            long start = System.currentTimeMillis();
            for(int i = 0; i < totalShards; i++) {
                if(MantaroData.config().get().upToShard != 0 && i > MantaroData.config().get().upToShard) continue;

                log.info("Starting shard #" + i + " of " + totalShards);
                MantaroEventManager manager = new MantaroEventManager();
                managers.add(manager);
                shards[i] = new MantaroShard(i, totalShards, manager, processor);
                log.debug("Finished loading shard #" + i + ".");
            }

            this.startPostLoadProcedure(start);
        } catch(Exception e) {
            e.printStackTrace();
            SentryHelper.captureExceptionContext("Shards failed to initialize!", e, this.getClass(), "Shard Loader");
        }
    }

    private void startPostLoadProcedure(long start) {
        long end = System.currentTimeMillis();
        MantaroBot bot = MantaroBot.getInstance();
        System.out.println("[-=-=-=-=-=- MANTARO STARTED -=-=-=-=-=-]");
        LogUtils.shard("Loaded all shards in " + ((end - start) / 1000) + " seconds.");
        bot.getCore().markAsReady();
        log.info("Loaded all shards succesfully... Starting ShardWatcher! Status: {}", MantaroCore.getLoadState());
        Async.thread("ShardWatcherThread", new ShardWatcher());
        bot.getCore().getShardEventBus().post(new PostLoadEvent());
        startUpdaters();
        bot.startCheckingBirthdays();
    }

    public void startUpdaters() {
        Async.task("Carbonitex post task", carbonitex::handle, 30, TimeUnit.MINUTES);

        for(MantaroShard shard : getShards()) {
            shard.updateServerCount();
            shard.updateStatus();
        }
    }
}

/**
 * Copyright 2014 Nikita Koksharov, Nickolay Borbit
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.redisson;

import java.util.List;

import org.redisson.client.codec.Codec;
import org.redisson.connection.ConnectionManager;
import org.redisson.core.RAtomicLongAsync;
import org.redisson.core.RBatch;
import org.redisson.core.RBlockingQueueAsync;
import org.redisson.core.RBucketAsync;
import org.redisson.core.RDequeAsync;
import org.redisson.core.RHyperLogLogAsync;
import org.redisson.core.RKeysAsync;
import org.redisson.core.RLexSortedSetAsync;
import org.redisson.core.RListAsync;
import org.redisson.core.RMapAsync;
import org.redisson.core.RQueueAsync;
import org.redisson.core.RScoredSortedSetAsync;
import org.redisson.core.RScriptAsync;
import org.redisson.core.RSetAsync;
import org.redisson.core.RTopicAsync;

import io.netty.util.concurrent.Future;

public class RedissonBatch implements RBatch {

    private final CommandBatchExecutorService executorService;

    public RedissonBatch(ConnectionManager connectionManager) {
        this.executorService = new CommandBatchExecutorService(connectionManager);
    }

    @Override
    public <V> RBucketAsync<V> getBucket(String name) {
        return new RedissonBucket<V>(executorService, name);
    }

    public <V> RBucketAsync<V> getBucket(String name, Codec codec) {
        return new RedissonBucket<V>(codec, executorService, name);
    }

    @Override
    public <V> RHyperLogLogAsync<V> getHyperLogLog(String name) {
        return new RedissonHyperLogLog<V>(executorService, name);
    }

    public <V> RHyperLogLogAsync<V> getHyperLogLog(String name, Codec codec) {
        return new RedissonHyperLogLog<V>(codec, executorService, name);
    }

    @Override
    public <V> RListAsync<V> getList(String name) {
        return new RedissonList<V>(executorService, name);
    }

    public <V> RListAsync<V> getList(String name, Codec codec) {
        return new RedissonList<V>(codec, executorService, name);
    }

    @Override
    public <K, V> RMapAsync<K, V> getMap(String name) {
        return new RedissonMap<K, V>(executorService, name);
    }

    public <K, V> RMapAsync<K, V> getMap(String name, Codec codec) {
        return new RedissonMap<K, V>(codec, executorService, name);
    }

    @Override
    public <V> RSetAsync<V> getSet(String name) {
        return new RedissonSet<V>(executorService, name);
    }

    public <V> RSetAsync<V> getSet(String name, Codec codec) {
        return new RedissonSet<V>(codec, executorService, name);
    }

    @Override
    public <M> RTopicAsync<M> getTopic(String name) {
        return new RedissonTopic<M>(executorService, name);
    }

    public <M> RTopicAsync<M> getTopic(String name, Codec codec) {
        return new RedissonTopic<M>(codec, executorService, name);
    }

    @Override
    public <V> RQueueAsync<V> getQueue(String name) {
        return new RedissonQueue<V>(executorService, name);
    }

    public <V> RQueueAsync<V> getQueue(String name, Codec codec) {
        return new RedissonQueue<V>(codec, executorService, name);
    }

    @Override
    public <V> RBlockingQueueAsync<V> getBlockingQueue(String name) {
        return new RedissonBlockingQueue<V>(executorService, name);
    }

    public <V> RBlockingQueueAsync<V> getBlockingQueue(String name, Codec codec) {
        return new RedissonBlockingQueue<V>(codec, executorService, name);
    }

    @Override
    public <V> RDequeAsync<V> getDequeAsync(String name) {
        return new RedissonDeque<V>(executorService, name);
    }

    public <V> RDequeAsync<V> getDequeAsync(String name, Codec codec) {
        return new RedissonDeque<V>(codec, executorService, name);
    }

    @Override
    public RAtomicLongAsync getAtomicLongAsync(String name) {
        return new RedissonAtomicLong(executorService, name);
    }

    @Override
    public <V> RScoredSortedSetAsync<V> getScoredSortedSet(String name) {
        return new RedissonScoredSortedSet<V>(executorService, name);
    }

    public <V> RScoredSortedSetAsync<V> getScoredSortedSet(String name, Codec codec) {
        return new RedissonScoredSortedSet<V>(codec, executorService, name);
    }

    @Override
    public RLexSortedSetAsync getLexSortedSet(String name) {
        return new RedissonLexSortedSet(executorService, name);
    }

    @Override
    public RScriptAsync getScript() {
        return new RedissonScript(executorService);
    }

    @Override
    public RKeysAsync getKeys() {
        return new RedissonKeys(executorService);
    }

    @Override
    public List<?> execute() {
        return executorService.execute();
    }

    @Override
    public Future<List<?>> executeAsync() {
        return executorService.executeAsync();
    }

}

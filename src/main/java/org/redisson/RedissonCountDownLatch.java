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

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import org.redisson.client.BaseRedisPubSubListener;
import org.redisson.client.RedisPubSubListener;
import org.redisson.client.codec.LongCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.client.protocol.pubsub.PubSubType;
import org.redisson.core.RCountDownLatch;

import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;

/**
 * Distributed alternative to the {@link java.util.concurrent.CountDownLatch}
 *
 * It has a advantage over {@link java.util.concurrent.CountDownLatch} --
 * count can be reset via {@link #trySetCount}.
 *
 * @author Nikita Koksharov
 *
 */
public class RedissonCountDownLatch extends RedissonObject implements RCountDownLatch {

    private static final Integer zeroCountMessage = 0;
    private static final Integer newCountMessage = 1;

    private static final ConcurrentMap<String, RedissonCountDownLatchEntry> ENTRIES = PlatformDependent.newConcurrentHashMap();

    private final UUID id;

    protected RedissonCountDownLatch(CommandExecutor commandExecutor, String name, UUID id) {
        super(commandExecutor, name);
        this.id = id;
    }

    private Future<RedissonCountDownLatchEntry> subscribe() {
        synchronized (ENTRIES) {
            RedissonCountDownLatchEntry entry = ENTRIES.get(getEntryName());
            if (entry != null) {
                entry.aquire();
                return entry.getPromise();
            }

            Promise<RedissonCountDownLatchEntry> newPromise = newPromise();
            final RedissonCountDownLatchEntry value = new RedissonCountDownLatchEntry(newPromise);
            value.aquire();

            RedissonCountDownLatchEntry oldValue = ENTRIES.putIfAbsent(getEntryName(), value);
            if (oldValue != null) {
                oldValue.aquire();
                return oldValue.getPromise();
            }

            RedisPubSubListener<Integer> listener = createListener(value);

            commandExecutor.getConnectionManager().subscribe(listener, getChannelName());
            return newPromise;
        }
    }

    private RedisPubSubListener<Integer> createListener(final RedissonCountDownLatchEntry value) {
        RedisPubSubListener<Integer> listener = new BaseRedisPubSubListener<Integer>() {

            @Override
            public void onMessage(String channel, Integer message) {
                if (!getChannelName().equals(channel)) {
                    return;
                }
                if (message.equals(zeroCountMessage)) {
                    value.getLatch().open();
                }
                if (message.equals(newCountMessage)) {
                    value.getLatch().close();
                }
            }

            @Override
            public boolean onStatus(PubSubType type, String channel) {
                if (channel.equals(getChannelName())
                        && type == PubSubType.SUBSCRIBE) {
                    value.getPromise().trySuccess(value);
                    return true;
                }
                return false;
            }

        };
        return listener;
    }

    private void unsubscribe(RedissonCountDownLatchEntry entry) {
        synchronized (ENTRIES) {
            if (entry.release() == 0) {
                // just an assertion
                boolean removed = ENTRIES.remove(getEntryName()) == entry;
                if (removed) {
                    commandExecutor.getConnectionManager().unsubscribe(getChannelName());
                }
            }
        }
    }

    public void await() throws InterruptedException {
        Future<RedissonCountDownLatchEntry> promise = subscribe();
        try {
            promise.await();

            while (getCountInner() > 0) {
                // waiting for open state
                RedissonCountDownLatchEntry entry = ENTRIES.get(getEntryName());
                if (entry != null) {
                    entry.getLatch().await();
                }
            }
        } finally {
            unsubscribe(promise.getNow());
        }
    }


    @Override
    public boolean await(long time, TimeUnit unit) throws InterruptedException {
        Future<RedissonCountDownLatchEntry> promise = subscribe();
        try {
            if (!promise.await(time, unit)) {
                return false;
            }

            time = unit.toMillis(time);
            while (getCountInner() > 0) {
                if (time <= 0) {
                    return false;
                }
                long current = System.currentTimeMillis();
                // waiting for open state
                RedissonCountDownLatchEntry entry = ENTRIES.get(getEntryName());
                if (entry != null) {
                    entry.getLatch().await(time, TimeUnit.MILLISECONDS);
                }

                long elapsed = System.currentTimeMillis() - current;
                time = time - elapsed;
            }

            return true;
        } finally {
            unsubscribe(promise.getNow());
        }
    }

    @Override
    public void countDown() {
        if (getCount() <= 0) {
            return;
        }

        commandExecutor.evalWrite(getName(), RedisCommands.EVAL_BOOLEAN_R1,
                "local v = redis.call('decr', KEYS[1]);" +
                        "if v <= 0 then redis.call('del', KEYS[1]) end;" +
                        "if v == 0 then redis.call('publish', ARGV[2], ARGV[1]) end;" +
                        "return true",
                 Collections.<Object>singletonList(getName()), zeroCountMessage, getChannelName());
    }

    private String getEntryName() {
        return id + getName();
    }

    private String getChannelName() {
        return "redisson_countdownlatch_{" + getName() + "}";
    }

    @Override
    public long getCount() {
        return getCountInner();
    }

    private long getCountInner() {
        Long val = commandExecutor.read(getName(), LongCodec.INSTANCE, RedisCommands.GET, getName());
        if (val == null) {
            return 0;
        }
        return val;
    }

    @Override
    public boolean trySetCount(long count) {
        return commandExecutor.evalWrite(getName(), RedisCommands.EVAL_BOOLEAN_R1,
                "if redis.call('exists', KEYS[1]) == 0 then redis.call('set', KEYS[1], ARGV[2]); redis.call('publish', ARGV[3], ARGV[1]); return true else return false end",
                 Collections.<Object>singletonList(getName()), newCountMessage, count, getChannelName());
    }

    @Override
    public Future<Boolean> deleteAsync() {
        return commandExecutor.evalWriteAsync(getName(), RedisCommands.EVAL_BOOLEAN_R1,
                "if redis.call('del', KEYS[1]) == 1 then redis.call('publish', ARGV[2], ARGV[1]); return true else return false end",
                 Collections.<Object>singletonList(getName()), newCountMessage, getChannelName());
    }

}

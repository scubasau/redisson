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

import java.util.Date;
import java.util.concurrent.TimeUnit;

import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.core.RExpirable;

import io.netty.util.concurrent.Future;

abstract class RedissonExpirable extends RedissonObject implements RExpirable {

    RedissonExpirable(CommandExecutor connectionManager, String name) {
        super(connectionManager, name);
    }

    RedissonExpirable(Codec codec, CommandExecutor connectionManager, String name) {
        super(codec, connectionManager, name);
    }

    @Override
    public boolean expire(long timeToLive, TimeUnit timeUnit) {
        return commandExecutor.get(expireAsync(timeToLive, timeUnit));
    }

    @Override
    public Future<Boolean> expireAsync(long timeToLive, TimeUnit timeUnit) {
        return commandExecutor.writeAsync(getName(), StringCodec.INSTANCE, RedisCommands.EXPIRE, getName(), timeUnit.toSeconds(timeToLive));
    }

    @Override
    public boolean expireAt(long timestamp) {
        return commandExecutor.get(expireAtAsync(timestamp));
    }

    @Override
    public Future<Boolean> expireAtAsync(long timestamp) {
        return commandExecutor.writeAsync(getName(), StringCodec.INSTANCE, RedisCommands.EXPIREAT, getName(), timestamp);
    }

    @Override
    public boolean expireAt(Date timestamp) {
        return expireAt(timestamp.getTime() / 1000);
    }

    @Override
    public Future<Boolean> expireAtAsync(Date timestamp) {
        return expireAtAsync(timestamp.getTime() / 1000);
    }

    @Override
    public boolean clearExpire() {
        return commandExecutor.get(clearExpireAsync());
    }

    @Override
    public Future<Boolean> clearExpireAsync() {
        return commandExecutor.writeAsync(getName(), StringCodec.INSTANCE, RedisCommands.PERSIST, getName());
    }

    @Override
    public long remainTimeToLive() {
        return commandExecutor.get(remainTimeToLiveAsync());
    }

    @Override
    public Future<Long> remainTimeToLiveAsync() {
        return commandExecutor.readAsync(getName(), StringCodec.INSTANCE, RedisCommands.TTL, getName());
    }

}

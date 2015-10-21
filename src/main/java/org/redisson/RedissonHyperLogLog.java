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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.core.RHyperLogLog;

import io.netty.util.concurrent.Future;

public class RedissonHyperLogLog<V> extends RedissonExpirable implements RHyperLogLog<V> {

    protected RedissonHyperLogLog(CommandExecutor commandExecutor, String name) {
        super(commandExecutor, name);
    }

    protected RedissonHyperLogLog(Codec codec, CommandExecutor commandExecutor, String name) {
        super(codec, commandExecutor, name);
    }

    @Override
    public boolean add(V obj) {
        return get(addAsync(obj));
    }

    @Override
    public boolean addAll(Collection<V> objects) {
        return get(addAllAsync(objects));
    }

    @Override
    public long count() {
        return get(countAsync());
    }

    @Override
    public long countWith(String... otherLogNames) {
        return get(countWithAsync(otherLogNames));
    }

    @Override
    public void mergeWith(String... otherLogNames) {
        get(mergeWithAsync(otherLogNames));
    }

    @Override
    public Future<Boolean> addAsync(V obj) {
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.PFADD, getName(), obj);
    }

    @Override
    public Future<Boolean> addAllAsync(Collection<V> objects) {
        List<Object> args = new ArrayList<Object>(objects.size() + 1);
        args.add(getName());
        args.addAll(objects);
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.PFADD, getName(), args.toArray());
    }

    @Override
    public Future<Long> countAsync() {
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.PFCOUNT, getName());
    }

    @Override
    public Future<Long> countWithAsync(String... otherLogNames) {
        List<Object> args = new ArrayList<Object>(otherLogNames.length + 1);
        args.add(getName());
        args.addAll(Arrays.asList(otherLogNames));
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.PFCOUNT, args.toArray());
    }

    @Override
    public Future<Void> mergeWithAsync(String... otherLogNames) {
        List<Object> args = new ArrayList<Object>(otherLogNames.length + 1);
        args.add(getName());
        args.addAll(Arrays.asList(otherLogNames));
        return commandExecutor.writeAsync(getName(), codec, RedisCommands.PFMERGE, args.toArray());
    }

}

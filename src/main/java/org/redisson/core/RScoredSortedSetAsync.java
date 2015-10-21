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
package org.redisson.core;

import java.util.Collection;

import org.redisson.client.protocol.ScoredEntry;

import io.netty.util.concurrent.Future;

public interface RScoredSortedSetAsync<V> extends RExpirableAsync {

    Future<V> firstAsync();

    Future<V> lastAsync();

    Future<Integer> removeRangeByRankAsync(int startIndex, int endIndex);

    Future<Integer> rankAsync(V o);

    Future<Double> getScoreAsync(V o);

    Future<Boolean> addAsync(double score, V object);

    Future<Boolean> removeAsync(V object);

    Future<Integer> sizeAsync();

    Future<Boolean> containsAsync(Object o);

    Future<Boolean> containsAllAsync(Collection<?> c);

    Future<Boolean> removeAllAsync(Collection<?> c);

    Future<Boolean> retainAllAsync(Collection<?> c);

    Future<Double> addScoreAsync(V object, Number value);

    Future<Collection<V>> valueRangeAsync(int startIndex, int endIndex);

    Future<Collection<ScoredEntry<V>>> entryRangeAsync(int startIndex, int endIndex);

}

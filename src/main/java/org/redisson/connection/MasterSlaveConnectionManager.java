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
package org.redisson.connection;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;

import org.redisson.Config;
import org.redisson.MasterSlaveServersConfig;
import org.redisson.client.BaseRedisPubSubListener;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisEmptySlotException;
import org.redisson.client.RedisPubSubConnection;
import org.redisson.client.RedisPubSubListener;
import org.redisson.client.codec.Codec;
import org.redisson.client.protocol.pubsub.PubSubType;
import org.redisson.misc.InfinitySemaphoreLatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.epoll.EpollSocketChannel;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import io.netty.util.TimerTask;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.FutureListener;
import io.netty.util.concurrent.Promise;
import io.netty.util.internal.PlatformDependent;

/**
 *
 * @author Nikita Koksharov
 *
 */
public class MasterSlaveConnectionManager implements ConnectionManager {

    static final int MAX_SLOT = 16384;

    private final Logger log = LoggerFactory.getLogger(getClass());

    private HashedWheelTimer timer;

    protected Codec codec;

    protected EventLoopGroup group;


    protected Class<? extends SocketChannel> socketChannelClass;

    protected final ConcurrentMap<String, PubSubConnectionEntry> name2PubSubConnection = PlatformDependent.newConcurrentHashMap();

    protected MasterSlaveServersConfig config;

    protected final NavigableMap<Integer, MasterSlaveEntry> entries = new ConcurrentSkipListMap<Integer, MasterSlaveEntry>();

    private final InfinitySemaphoreLatch shutdownLatch = new InfinitySemaphoreLatch();

    private final Set<RedisClientEntry> clients = Collections.newSetFromMap(new ConcurrentHashMap<RedisClientEntry, Boolean>());

    MasterSlaveConnectionManager() {
    }

    @Override
    public HashedWheelTimer getTimer() {
        return timer;
    }

    @Override
    public MasterSlaveServersConfig getConfig() {
        return config;
    }

    @Override
    public Codec getCodec() {
        return codec;
    }

    @Override
    public NavigableMap<Integer, MasterSlaveEntry> getEntries() {
        return entries;
    }

    public MasterSlaveConnectionManager(MasterSlaveServersConfig cfg, Config config) {
        init(cfg, config);
    }

    protected void init(MasterSlaveServersConfig config, Config cfg) {
        init(cfg);
        init(config);
    }

    protected void init(MasterSlaveServersConfig config) {
        this.config = config;

        int minTimeout = Math.min(config.getRetryInterval(), config.getTimeout());
        if (minTimeout % 100 != 0) {
            timer = new HashedWheelTimer((minTimeout % 100) / 2, TimeUnit.MILLISECONDS);
        } else {
            timer = new HashedWheelTimer(100, TimeUnit.MILLISECONDS);
        }

        initEntry(config);
    }

    protected void initEntry(MasterSlaveServersConfig config) {
        MasterSlaveEntry entry = new MasterSlaveEntry(0, MAX_SLOT, this, config);
        entry.setupMasterEntry(config.getMasterAddress().getHost(), config.getMasterAddress().getPort());
        entries.put(MAX_SLOT, entry);
    }

    protected void init(Config cfg) {
        if (cfg.isUseLinuxNativeEpoll()) {
            this.group = new EpollEventLoopGroup(cfg.getThreads());
            this.socketChannelClass = EpollSocketChannel.class;
        } else {
            this.group = new NioEventLoopGroup(cfg.getThreads());
            this.socketChannelClass = NioSocketChannel.class;
        }
        this.codec = cfg.getCodec();
    }

    @Override
    public RedisClient createClient(String host, int port) {
        RedisClient client = createClient(host, port, config.getTimeout());
        clients.add(new RedisClientEntry(client));
        return client;
    }

    public void shutdownAsync(RedisClient client) {
        clients.remove(new RedisClientEntry(client));
        client.shutdownAsync();
    }

    @Override
    public RedisClient createClient(String host, int port, int timeout) {
        return new RedisClient(group, socketChannelClass, host, port, timeout);
    }

    @Override
    public <T> FutureListener<T> createReleaseWriteListener(final int slot,
                                    final RedisConnection conn, final Timeout timeout) {
        return new FutureListener<T>() {
            @Override
            public void operationComplete(io.netty.util.concurrent.Future<T> future) throws Exception {
                if (!future.isSuccess()) {
                    conn.incFailAttempt();
                } else {
                    conn.resetFailAttempt();
                }

                shutdownLatch.release();
                timeout.cancel();
                releaseWrite(slot, conn);
            }
        };
    }

    @Override
    public <T> FutureListener<T> createReleaseReadListener(final int slot,
                                    final RedisConnection conn, final Timeout timeout) {
        return new FutureListener<T>() {
            @Override
            public void operationComplete(io.netty.util.concurrent.Future<T> future) throws Exception {
                if (!future.isSuccess()) {
                    conn.incFailAttempt();
                } else {
                    conn.resetFailAttempt();
                }

                shutdownLatch.release();
                timeout.cancel();
                releaseRead(slot, conn);
            }
        };
    }

    @Override
    public int calcSlot(String key) {
        if (entries.size() == 1 || key == null) {
            return 0;
        }

        int start = key.indexOf('{');
        if (start != -1) {
            int end = key.indexOf('}');
            key = key.substring(start+1, end);
        }

        int result = CRC16.crc16(key.getBytes()) % MAX_SLOT;
        log.debug("slot {} for {}", result, key);
        return result;
    }

    @Override
    public PubSubConnectionEntry getEntry(String channelName) {
        return name2PubSubConnection.get(channelName);
    }

    public Future<PubSubConnectionEntry> subscribe(String channelName, Codec codec) {
        Promise<PubSubConnectionEntry> promise = group.next().newPromise();
        subscribe(channelName, codec, promise);
        return promise;
    }

    private void subscribe(final String channelName, final Codec codec, final Promise<PubSubConnectionEntry> promise) {
        // multiple channel names per PubSubConnections allowed
        PubSubConnectionEntry сonnEntry = name2PubSubConnection.get(channelName);
        if (сonnEntry != null) {
            promise.setSuccess(сonnEntry);
            return;
        }

        Set<PubSubConnectionEntry> entries = new HashSet<PubSubConnectionEntry>(name2PubSubConnection.values());
        for (PubSubConnectionEntry entry : entries) {
            if (entry.tryAcquire()) {
                PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
                if (oldEntry != null) {
                    entry.release();
                    promise.setSuccess(oldEntry);
                    return;
                }

                synchronized (entry) {
                    if (!entry.isActive()) {
                        entry.release();
                        subscribe(channelName, codec, promise);
                        return;
                    }
                    entry.subscribe(codec, channelName);
                    promise.setSuccess(entry);
                    return;
                }
            }
        }

        final int slot = 0;
        Future<RedisPubSubConnection> connFuture = nextPubSubConnection(slot);
        connFuture.addListener(new FutureListener<RedisPubSubConnection>() {
            @Override
            public void operationComplete(Future<RedisPubSubConnection> future) throws Exception {
                if (!future.isSuccess()) {
                    promise.setFailure(future.cause());
                    return;
                }

                RedisPubSubConnection conn = future.getNow();

                PubSubConnectionEntry entry = new PubSubConnectionEntry(conn, config.getSubscriptionsPerConnection());
                entry.tryAcquire();
                PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
                if (oldEntry != null) {
                    releaseSubscribeConnection(slot, entry);
                    promise.setSuccess(oldEntry);
                    return;
                }

                synchronized (entry) {
                    if (!entry.isActive()) {
                        entry.release();
                        subscribe(channelName, codec, promise);
                        return;
                    }
                    entry.subscribe(codec, channelName);
                    promise.setSuccess(entry);
                }
            }
        });
    }

    @Override
    public Future<PubSubConnectionEntry> psubscribe(final String channelName, final Codec codec) {
        Promise<PubSubConnectionEntry> promise = group.next().newPromise();
        psubscribe(channelName, codec, promise);
        return promise;
    }

    private void psubscribe(final String channelName, final Codec codec, final Promise<PubSubConnectionEntry> promise) {
        // multiple channel names per PubSubConnections are allowed
        PubSubConnectionEntry сonnEntry = name2PubSubConnection.get(channelName);
        if (сonnEntry != null) {
            promise.setSuccess(сonnEntry);
            return;
        }

        Set<PubSubConnectionEntry> entries = new HashSet<PubSubConnectionEntry>(name2PubSubConnection.values());
        for (PubSubConnectionEntry entry : entries) {
            if (entry.tryAcquire()) {
                PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
                if (oldEntry != null) {
                    entry.release();
                    promise.setSuccess(oldEntry);
                    return;
                }

                synchronized (entry) {
                    if (!entry.isActive()) {
                        entry.release();
                        psubscribe(channelName, codec, promise);
                        return;
                    }
                    entry.psubscribe(codec, channelName);
                    promise.setSuccess(entry);
                    return;
                }
            }
        }

        final int slot = 0;
        Future<RedisPubSubConnection> connFuture = nextPubSubConnection(slot);
        connFuture.addListener(new FutureListener<RedisPubSubConnection>() {
            @Override
            public void operationComplete(Future<RedisPubSubConnection> future) throws Exception {
                if (!future.isSuccess()) {
                    promise.setFailure(future.cause());
                    return;
                }

                RedisPubSubConnection conn = future.getNow();

                PubSubConnectionEntry entry = new PubSubConnectionEntry(conn, config.getSubscriptionsPerConnection());
                entry.tryAcquire();
                PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
                if (oldEntry != null) {
                    releaseSubscribeConnection(slot, entry);
                    promise.setSuccess(oldEntry);
                    return;
                }

                synchronized (entry) {
                    if (!entry.isActive()) {
                        entry.release();
                        psubscribe(channelName, codec, promise);
                        return;
                    }
                    entry.psubscribe(codec, channelName);
                    promise.setSuccess(entry);
                }
            }
        });
    }

    @Override
    public void subscribe(final RedisPubSubListener listener, final String channelName) {
        PubSubConnectionEntry сonnEntry = name2PubSubConnection.get(channelName);
        if (сonnEntry != null) {
            сonnEntry.subscribe(codec, listener, channelName);
            return;
        }

        Set<PubSubConnectionEntry> entries = new HashSet<PubSubConnectionEntry>(name2PubSubConnection.values());
        for (PubSubConnectionEntry entry : entries) {
            if (entry.tryAcquire()) {
                PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
                if (oldEntry != null) {
                    entry.release();
                    return;
                }
                synchronized (entry) {
                    if (!entry.isActive()) {
                        entry.release();
                        subscribe(listener, channelName);
                        return;
                    }
                    entry.subscribe(codec, listener, channelName);
                    return;
                }
            }
        }

        final int slot = 0;
        Future<RedisPubSubConnection> connFuture = nextPubSubConnection(slot);
        connFuture.syncUninterruptibly();
        RedisPubSubConnection conn = connFuture.getNow();
        PubSubConnectionEntry entry = new PubSubConnectionEntry(conn, config.getSubscriptionsPerConnection());
        entry.tryAcquire();
        PubSubConnectionEntry oldEntry = name2PubSubConnection.putIfAbsent(channelName, entry);
        if (oldEntry != null) {
            releaseSubscribeConnection(slot, entry);
            return;
        }
        synchronized (entry) {
            if (!entry.isActive()) {
                entry.release();
                subscribe(listener, channelName);
                return;
            }
            entry.subscribe(codec, listener, channelName);
            return;
        }

    }

    @Override
    public Codec unsubscribe(final String channelName) {
        final PubSubConnectionEntry entry = name2PubSubConnection.remove(channelName);
        if (entry == null) {
            return null;
        }

        Codec entryCodec = entry.getConnection().getChannels().get(channelName);
        entry.unsubscribe(channelName, new BaseRedisPubSubListener() {

            @Override
            public boolean onStatus(PubSubType type, String channel) {
                if (type == PubSubType.UNSUBSCRIBE && channel.equals(channelName)) {
                    synchronized (entry) {
                        if (entry.tryClose()) {
                            releaseSubscribeConnection(0, entry);
                        }
                    }
                    return true;
                }
                return false;
            }

        });
        return entryCodec;
    }

    @Override
    public Codec punsubscribe(final String channelName) {
        final PubSubConnectionEntry entry = name2PubSubConnection.remove(channelName);
        if (entry == null) {
            return null;
        }

        Codec entryCodec = entry.getConnection().getPatternChannels().get(channelName);
        entry.punsubscribe(channelName, new BaseRedisPubSubListener() {

            @Override
            public boolean onStatus(PubSubType type, String channel) {
                if (type == PubSubType.PUNSUBSCRIBE && channel.equals(channelName)) {
                    synchronized (entry) {
                        if (entry.tryClose()) {
                            releaseSubscribeConnection(0, entry);
                        }
                    }
                    return true;
                }
                return false;
            }

        });
        return entryCodec;
    }

    protected MasterSlaveEntry getEntry(int slot) {
        return entries.ceilingEntry(slot).getValue();
    }

    protected void slaveDown(int slot, String host, int port) {
        Collection<RedisPubSubConnection> allPubSubConnections = getEntry(slot).slaveDown(host, port);

        // reattach listeners to other channels
        for (Entry<String, PubSubConnectionEntry> mapEntry : name2PubSubConnection.entrySet()) {
            for (RedisPubSubConnection redisPubSubConnection : allPubSubConnections) {
                PubSubConnectionEntry entry = mapEntry.getValue();
                final String channelName = mapEntry.getKey();

                if (!entry.getConnection().equals(redisPubSubConnection)) {
                    continue;
                }

                synchronized (entry) {
                    entry.close();

                    final Collection<RedisPubSubListener> listeners = entry.getListeners(channelName);
                    if (entry.getConnection().getPatternChannels().get(channelName) != null) {
                        Codec subscribeCodec = punsubscribe(channelName);
                        if (!listeners.isEmpty()) {
                            Future<PubSubConnectionEntry> future = psubscribe(channelName, subscribeCodec);
                            future.addListener(new FutureListener<PubSubConnectionEntry>() {
                                @Override
                                public void operationComplete(Future<PubSubConnectionEntry> future)
                                        throws Exception {
                                    PubSubConnectionEntry newEntry = future.getNow();
                                    for (RedisPubSubListener redisPubSubListener : listeners) {
                                        newEntry.addListener(channelName, redisPubSubListener);
                                    }
                                    log.debug("resubscribed listeners for '{}' channel-pattern", channelName);
                                }
                            });
                        }
                    } else {
                        Codec subscribeCodec = unsubscribe(channelName);
                        if (!listeners.isEmpty()) {
                            Future<PubSubConnectionEntry> future = subscribe(channelName, subscribeCodec);
                            future.addListener(new FutureListener<PubSubConnectionEntry>() {

                                @Override
                                public void operationComplete(Future<PubSubConnectionEntry> future)
                                        throws Exception {
                                    PubSubConnectionEntry newEntry = future.getNow();
                                    for (RedisPubSubListener redisPubSubListener : listeners) {
                                        newEntry.addListener(channelName, redisPubSubListener);
                                    }
                                    log.debug("resubscribed listeners for '{}' channel", channelName);
                                }
                            });
                        }
                    }
                }
            }
        }
    }

    protected void changeMaster(int endSlot, String host, int port) {
        getEntry(endSlot).changeMaster(host, port);
    }

    protected MasterSlaveEntry removeMaster(int endSlot) {
        return entries.remove(endSlot);
    }

    @Override
    public Future<RedisConnection> connectionWriteOp(int slot) {
        MasterSlaveEntry e = getEntry(slot);
        if (!e.isOwn(slot)) {
            throw new RedisEmptySlotException("No node for slot: " + slot, slot);
        }
        return e.connectionWriteOp();
    }

    @Override
    public Future<RedisConnection> connectionReadOp(int slot) {
        MasterSlaveEntry e = getEntry(slot);
        if (!e.isOwn(slot)) {
            throw new RedisEmptySlotException("No node for slot: " + slot, slot);
        }
        return e.connectionReadOp();
    }

    @Override
    public Future<RedisConnection> connectionReadOp(int slot, RedisClient client) {
        MasterSlaveEntry e = getEntry(slot);
        if (!e.isOwn(slot)) {
            throw new RedisEmptySlotException("No node for slot: " + slot, slot);
        }
        return e.connectionReadOp(client);
    }

    Future<RedisPubSubConnection> nextPubSubConnection(int slot) {
        return getEntry(slot).nextPubSubConnection();
    }

    protected void releaseSubscribeConnection(int slot, PubSubConnectionEntry entry) {
        this.getEntry(slot).returnSubscribeConnection(entry);
    }

    @Override
    public void releaseWrite(int slot, RedisConnection connection) {
        getEntry(slot).releaseWrite(connection);
    }

    @Override
    public void releaseRead(int slot, RedisConnection connection) {
        getEntry(slot).releaseRead(connection);
    }

    @Override
    public void shutdown() {
        shutdownLatch.closeAndAwaitUninterruptibly();
        for (MasterSlaveEntry entry : entries.values()) {
            entry.shutdown();
        }
        timer.stop();
        group.shutdownGracefully().syncUninterruptibly();
    }

    public Collection<RedisClientEntry> getClients() {
        return Collections.unmodifiableCollection(clients);
    }

    @Override
    public <R> Promise<R> newPromise() {
        return group.next().newPromise();
    }

    @Override
    public EventLoopGroup getGroup() {
        return group;
    }

    @Override
    public Timeout newTimeout(TimerTask task, long delay, TimeUnit unit) {
        return timer.newTimeout(task, delay, unit);
    }

    public InfinitySemaphoreLatch getShutdownLatch() {
        return shutdownLatch;
    }

}

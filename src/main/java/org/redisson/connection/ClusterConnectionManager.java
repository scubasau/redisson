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

import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.redisson.ClusterServersConfig;
import org.redisson.Config;
import org.redisson.MasterSlaveServersConfig;
import org.redisson.client.RedisClient;
import org.redisson.client.RedisConnection;
import org.redisson.client.RedisConnectionException;
import org.redisson.client.protocol.RedisCommands;
import org.redisson.connection.ClusterNodeInfo.Flag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ClusterConnectionManager extends MasterSlaveConnectionManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final Map<URI, RedisConnection> nodeConnections = new HashMap<URI, RedisConnection>();

    private final Map<Integer, ClusterPartition> lastPartitions = new HashMap<Integer, ClusterPartition>();

    private ScheduledFuture<?> monitorFuture;

    public ClusterConnectionManager(ClusterServersConfig cfg, Config config) {
        init(config);

        this.config = create(cfg);
        init(this.config);

        for (URI addr : cfg.getNodeAddresses()) {
            RedisConnection connection = connect(cfg, addr);
            if (connection == null) {
                continue;
            }

            String nodesValue = connection.sync(RedisCommands.CLUSTER_NODES);

            Map<Integer, ClusterPartition> partitions = parsePartitions(nodesValue);
            for (ClusterPartition partition : partitions.values()) {
                addMasterEntry(partition, cfg);
            }

            break;
        }

        monitorClusterChange(cfg);
    }

    private RedisConnection connect(ClusterServersConfig cfg, URI addr) {
        RedisConnection connection = nodeConnections.get(addr);
        if (connection != null) {
            return connection;
        }
        RedisClient client = createClient(addr.getHost(), addr.getPort(), cfg.getTimeout());
        try {
            connection = client.connect();
            nodeConnections.put(addr, connection);
        } catch (RedisConnectionException e) {
            log.warn(e.getMessage(), e);
        } catch (Exception e) {
            log.error(e.getMessage(), e);
        }
        return connection;
    }

    @Override
    protected void initEntry(MasterSlaveServersConfig config) {
    }

    private void addMasterEntry(ClusterPartition partition, ClusterServersConfig cfg) {
        if (partition.isMasterFail()) {
            log.warn("master: {} for slot range: {}-{} add failed. Reason - server has FAIL flag", partition.getMasterAddress(), partition.getStartSlot(), partition.getEndSlot());
            return;
        }

        RedisConnection connection = connect(cfg, partition.getMasterAddress());
        if (connection == null) {
            return;
        }
        Map<String, String> params = connection.sync(RedisCommands.CLUSTER_INFO);
        if ("fail".equals(params.get("cluster_state"))) {
            log.warn("master: {} for slot range: {}-{} add failed. Reason - cluster_state:fail", partition.getMasterAddress(), partition.getStartSlot(), partition.getEndSlot());
            return;
        }

        MasterSlaveServersConfig config = create(cfg);
        log.info("master: {} for slot range: {}-{} added", partition.getMasterAddress(), partition.getStartSlot(), partition.getEndSlot());
        config.setMasterAddress(partition.getMasterAddress());

        SingleEntry entry = new SingleEntry(partition.getStartSlot(), partition.getEndSlot(), this, config);
        entry.setupMasterEntry(config.getMasterAddress().getHost(), config.getMasterAddress().getPort());
        entries.put(partition.getEndSlot(), entry);
        lastPartitions.put(partition.getEndSlot(), partition);
    }

    private void monitorClusterChange(final ClusterServersConfig cfg) {
        monitorFuture = GlobalEventExecutor.INSTANCE.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    for (URI addr : cfg.getNodeAddresses()) {
                        RedisConnection connection = connect(cfg, addr);
                            String nodesValue = connection.sync(RedisCommands.CLUSTER_NODES);

                            log.debug("cluster nodes state: {}", nodesValue);

                            Map<Integer, ClusterPartition> partitions = parsePartitions(nodesValue);
                            for (ClusterPartition newPart : partitions.values()) {
                                for (ClusterPartition part : lastPartitions.values()) {
                                    if (newPart.getMasterAddress().equals(part.getMasterAddress())) {

                                        log.debug("found endslot {} for {} fail {}", part.getEndSlot(), part.getMasterAddress(), newPart.isMasterFail());

                                        if (newPart.isMasterFail()) {
                                            ClusterPartition newMasterPart = partitions.get(part.getEndSlot());
                                            if (!newMasterPart.getMasterAddress().equals(part.getMasterAddress())) {
                                                log.info("changing master from {} to {} for {}",
                                                        part.getMasterAddress(), newMasterPart.getMasterAddress(), newMasterPart.getEndSlot());
                                                URI newUri = newMasterPart.getMasterAddress();
                                                URI oldUri = part.getMasterAddress();

                                                changeMaster(newMasterPart.getEndSlot(), newUri.getHost(), newUri.getPort());
                                                slaveDown(newMasterPart.getEndSlot(), oldUri.getHost(), oldUri.getPort());

                                                part.setMasterAddress(newMasterPart.getMasterAddress());
                                            }
                                        }
                                        break;
                                    }
                                }
                            }

                            checkSlotsChange(cfg, partitions);

                            break;
                    }

                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }

            }

        }, cfg.getScanInterval(), cfg.getScanInterval(), TimeUnit.MILLISECONDS);
    }

    private void checkSlotsChange(ClusterServersConfig cfg, Map<Integer, ClusterPartition> partitions) {
        Set<Integer> removeSlots = new HashSet<Integer>(lastPartitions.keySet());
        removeSlots.removeAll(partitions.keySet());
        lastPartitions.keySet().removeAll(removeSlots);
        if (!removeSlots.isEmpty()) {
            log.info("{} slots found to remove", removeSlots.size());
        }

        Map<Integer, MasterSlaveEntry> removeAddrs = new HashMap<Integer, MasterSlaveEntry>();
        for (Integer slot : removeSlots) {
            MasterSlaveEntry entry = removeMaster(slot);
            entry.shutdownMasterAsync();
            removeAddrs.put(slot, entry);
        }

        Set<Integer> addSlots = new HashSet<Integer>(partitions.keySet());
        addSlots.removeAll(lastPartitions.keySet());
        if (!addSlots.isEmpty()) {
            log.info("{} slots found to add", addSlots.size());
        }
        for (Integer slot : addSlots) {
            ClusterPartition partition = partitions.get(slot);
            addMasterEntry(partition, cfg);
        }

        for (Entry<Integer, MasterSlaveEntry> entry : removeAddrs.entrySet()) {
            InetSocketAddress url = entry.getValue().getClient().getAddr();
            slaveDown(entry.getKey(), url.getHostName(), url.getPort());
        }
    }

    private Map<Integer, ClusterPartition> parsePartitions(String nodesValue) {
        Map<String, ClusterPartition> partitions = new HashMap<String, ClusterPartition>();
        Map<Integer, ClusterPartition> result = new HashMap<Integer, ClusterPartition>();
        List<ClusterNodeInfo> nodes = parse(nodesValue);
        for (ClusterNodeInfo clusterNodeInfo : nodes) {
            if (clusterNodeInfo.getFlags().contains(Flag.NOADDR)) {
                // skip it
                continue;
            }

            String id = clusterNodeInfo.getNodeId();
            if (clusterNodeInfo.getFlags().contains(Flag.SLAVE)) {
                id = clusterNodeInfo.getSlaveOf();
            }
            ClusterPartition partition = partitions.get(id);
            if (partition == null) {
                partition = new ClusterPartition();
                partitions.put(id, partition);
            }

            if (clusterNodeInfo.getFlags().contains(Flag.FAIL)) {
                partition.setMasterFail(true);
            }

            if (clusterNodeInfo.getFlags().contains(Flag.SLAVE)) {
                partition.addSlaveAddress(clusterNodeInfo.getAddress());
            } else {
                partition.setStartSlot(clusterNodeInfo.getStartSlot());
                partition.setEndSlot(clusterNodeInfo.getEndSlot());
                result.put(clusterNodeInfo.getEndSlot(), partition);
                partition.setMasterAddress(clusterNodeInfo.getAddress());
            }
        }
        return result;
    }

    private MasterSlaveServersConfig create(ClusterServersConfig cfg) {
        MasterSlaveServersConfig c = new MasterSlaveServersConfig();
        c.setRetryInterval(cfg.getRetryInterval());
        c.setRetryAttempts(cfg.getRetryAttempts());
        c.setTimeout(cfg.getTimeout());
        c.setPingTimeout(cfg.getPingTimeout());
        c.setLoadBalancer(cfg.getLoadBalancer());
        c.setPassword(cfg.getPassword());
        c.setDatabase(cfg.getDatabase());
        c.setClientName(cfg.getClientName());
        c.setRefreshConnectionAfterFails(cfg.getRefreshConnectionAfterFails());
        c.setMasterConnectionPoolSize(cfg.getMasterConnectionPoolSize());
        c.setSlaveConnectionPoolSize(cfg.getSlaveConnectionPoolSize());
        c.setSlaveSubscriptionConnectionPoolSize(cfg.getSlaveSubscriptionConnectionPoolSize());
        c.setSubscriptionsPerConnection(cfg.getSubscriptionsPerConnection());
        return c;
    }

    private List<ClusterNodeInfo> parse(String nodesResponse) {
        List<ClusterNodeInfo> nodes = new ArrayList<ClusterNodeInfo>();
        for (String nodeInfo : nodesResponse.split("\n")) {
            ClusterNodeInfo node = new ClusterNodeInfo();
            String[] params = nodeInfo.split(" ");

            String nodeId = params[0];
            node.setNodeId(nodeId);

            String addr = params[1];
            node.setAddress(addr);

            String flags = params[2];
            for (String flag : flags.split(",")) {
                String flagValue = flag.toUpperCase().replaceAll("\\?", "");
                node.addFlag(ClusterNodeInfo.Flag.valueOf(flagValue));
            }

            String slaveOf = params[3];
            if (!"-".equals(slaveOf)) {
                node.setSlaveOf(slaveOf);
            }

            if (params.length > 8) {
                String slots = params[8];
                String[] parts = slots.split("-");
                node.setStartSlot(Integer.valueOf(parts[0]));
                node.setEndSlot(Integer.valueOf(parts[1]));
            }

            nodes.add(node);
        }
        return nodes;
    }

    @Override
    public void shutdown() {
        monitorFuture.cancel(true);
        super.shutdown();

        for (RedisConnection connection : nodeConnections.values()) {
            connection.getRedisClient().shutdown();
        }
    }
}


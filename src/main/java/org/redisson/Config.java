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

import org.redisson.client.codec.Codec;
import org.redisson.codec.JsonJacksonCodec;

/**
 * Redisson configuration
 *
 * @author Nikita Koksharov
 *
 */
public class Config {

    private SentinelServersConfig sentinelServersConfig;

    private MasterSlaveServersConfig masterSlaveServersConfig;

    private SingleServerConfig singleServerConfig;

    private ClusterServersConfig clusterServersConfig;
    
    private ElasticacheServersConfig elasticacheServersConfig;

    /**
     * Threads amount shared between all redis node clients
     */
    private int threads = 0; // 0 = current_processors_amount * 2

    /**
     * Redis key/value codec. JsonJacksonCodec used by default
     */
    private Codec codec;

    private boolean useLinuxNativeEpoll;

    public Config() {
    }

    Config(Config oldConf) {
        setUseLinuxNativeEpoll(oldConf.isUseLinuxNativeEpoll());

        if (oldConf.getCodec() == null) {
            // use it by default
            oldConf.setCodec(new JsonJacksonCodec());
        }

        setThreads(oldConf.getThreads());
        setCodec(oldConf.getCodec());
        if (oldConf.getSingleServerConfig() != null) {
            setSingleServerConfig(new SingleServerConfig(oldConf.getSingleServerConfig()));
        }
        if (oldConf.getMasterSlaveServersConfig() != null) {
            setMasterSlaveServersConfig(new MasterSlaveServersConfig(oldConf.getMasterSlaveServersConfig()));
        }
        if (oldConf.getSentinelServersConfig() != null ) {
            setSentinelServersConfig(new SentinelServersConfig(oldConf.getSentinelServersConfig()));
        }
        if (oldConf.getClusterServersConfig() != null ) {
            setClusterServersConfig(new ClusterServersConfig(oldConf.getClusterServersConfig()));
        }
        if (oldConf.getElasticacheServersConfig() != null) {
            setElasticacheServersConfig(new ElasticacheServersConfig(oldConf.getElasticacheServersConfig()));
        }
        
    }

    /**
     * Redis key/value codec. Default is json
     *
     * @see org.redisson.codec.JsonJacksonCodec
     * @see org.redisson.codec.SerializationCodec
     */
    public Config setCodec(Codec codec) {
        this.codec = codec;
        return this;
    }
    public Codec getCodec() {
        return codec;
    }

    public ClusterServersConfig useClusterServers() {
        checkMasterSlaveServersConfig();
        checkSentinelServersConfig();
        checkSingleServerConfig();
        checkElasticacheServersConfig();

        if (clusterServersConfig == null) {
            clusterServersConfig = new ClusterServersConfig();
        }
        return clusterServersConfig;
    }

    ClusterServersConfig getClusterServersConfig() {
        return clusterServersConfig;
    }
    void setClusterServersConfig(ClusterServersConfig clusterServersConfig) {
        this.clusterServersConfig = clusterServersConfig;
    }

    public ElasticacheServersConfig useElasticacheServers() {
        checkClusterServersConfig();
        checkMasterSlaveServersConfig();
        checkSentinelServersConfig();
        checkSingleServerConfig();

        if (elasticacheServersConfig == null) {
            elasticacheServersConfig = new ElasticacheServersConfig();
        }
        return elasticacheServersConfig;
    }

    ElasticacheServersConfig getElasticacheServersConfig() {
        return elasticacheServersConfig;
    }
    void setElasticacheServersConfig(ElasticacheServersConfig elasticacheServersConfig) {
        this.elasticacheServersConfig = elasticacheServersConfig;
    }

    public SingleServerConfig useSingleServer() {
        checkClusterServersConfig();
        checkMasterSlaveServersConfig();
        checkSentinelServersConfig();
        checkElasticacheServersConfig();

        if (singleServerConfig == null) {
            singleServerConfig = new SingleServerConfig();
        }
        return singleServerConfig;
    }
    
    SingleServerConfig getSingleServerConfig() {
        return singleServerConfig;
    }
    void setSingleServerConfig(SingleServerConfig singleConnectionConfig) {
        this.singleServerConfig = singleConnectionConfig;
    }

    public SentinelServersConfig useSentinelConnection() {
        checkClusterServersConfig();
        checkSingleServerConfig();
        checkMasterSlaveServersConfig();
        checkElasticacheServersConfig();

        if (sentinelServersConfig == null) {
            sentinelServersConfig = new SentinelServersConfig();
        }
        return sentinelServersConfig;
    }

    SentinelServersConfig getSentinelServersConfig() {
        return sentinelServersConfig;
    }
    void setSentinelServersConfig(SentinelServersConfig sentinelConnectionConfig) {
        this.sentinelServersConfig = sentinelConnectionConfig;
    }

    public MasterSlaveServersConfig useMasterSlaveConnection() {
        checkClusterServersConfig();
        checkSingleServerConfig();
        checkSentinelServersConfig();
        checkElasticacheServersConfig();

        if (masterSlaveServersConfig == null) {
            masterSlaveServersConfig = new MasterSlaveServersConfig();
        }
        return masterSlaveServersConfig;
    }
    MasterSlaveServersConfig getMasterSlaveServersConfig() {
        return masterSlaveServersConfig;
    }
    void setMasterSlaveServersConfig(MasterSlaveServersConfig masterSlaveConnectionConfig) {
        this.masterSlaveServersConfig = masterSlaveConnectionConfig;
    }

    public boolean isClusterConfig() {
        return clusterServersConfig != null;
    }

    public int getThreads() {
        return threads;
    }

    public Config setThreads(int threads) {
        this.threads = threads;
        return this;
    }

    private void checkClusterServersConfig() {
        if (clusterServersConfig != null) {
            throw new IllegalStateException("cluster servers config already used!");
        }
    }

    private void checkSentinelServersConfig() {
        if (sentinelServersConfig != null) {
            throw new IllegalStateException("sentinel servers config already used!");
        }
    }

    private void checkMasterSlaveServersConfig() {
        if (masterSlaveServersConfig != null) {
            throw new IllegalStateException("master/slave servers already used!");
        }
    }

    private void checkSingleServerConfig() {
        if (singleServerConfig != null) {
            throw new IllegalStateException("single server config already used!");
        }
    }
    
    private void checkElasticacheServersConfig() {
        if (elasticacheServersConfig != null) {
            throw new IllegalStateException("elasticache replication group servers config already used!");
        }
    }

    /**
     * Activates an unix socket if servers binded to loopback interface.
     * Also used for epoll transport activation.
     * <b>netty-transport-native-epoll</b> library should be in classpath
     *
     * @param useLinuxNativeEpoll
     * @return
     */
    public Config setUseLinuxNativeEpoll(boolean useLinuxNativeEpoll) {
        this.useLinuxNativeEpoll = useLinuxNativeEpoll;
        return this;
    }
    public boolean isUseLinuxNativeEpoll() {
        return useLinuxNativeEpoll;
    }


}

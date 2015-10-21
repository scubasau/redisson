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

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.redisson.Config;
import org.redisson.MasterSlaveServersConfig;
import org.redisson.SingleServerConfig;
import org.redisson.client.RedisConnectionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.concurrent.GlobalEventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

public class SingleConnectionManager extends MasterSlaveConnectionManager {

    private final Logger log = LoggerFactory.getLogger(getClass());

    private final AtomicReference<InetAddress> currentMaster = new AtomicReference<InetAddress>();

    private ScheduledFuture<?> monitorFuture;

    public SingleConnectionManager(SingleServerConfig cfg, Config config) {
        MasterSlaveServersConfig newconfig = new MasterSlaveServersConfig();
        String addr = cfg.getAddress().getHost() + ":" + cfg.getAddress().getPort();
        newconfig.setRetryAttempts(cfg.getRetryAttempts());
        newconfig.setRetryInterval(cfg.getRetryInterval());
        newconfig.setTimeout(cfg.getTimeout());
        newconfig.setPingTimeout(cfg.getPingTimeout());
        newconfig.setPassword(cfg.getPassword());
        newconfig.setDatabase(cfg.getDatabase());
        newconfig.setClientName(cfg.getClientName());
        newconfig.setRefreshConnectionAfterFails(cfg.getRefreshConnectionAfterFails());
        newconfig.setMasterAddress(addr);
        newconfig.setMasterConnectionPoolSize(cfg.getConnectionPoolSize());
        newconfig.setSubscriptionsPerConnection(cfg.getSubscriptionsPerConnection());
        newconfig.setSlaveSubscriptionConnectionPoolSize(cfg.getSubscriptionConnectionPoolSize());

        init(newconfig, config);

        if (cfg.isDnsMonitoring()) {
            try {
                this.currentMaster.set(InetAddress.getByName(cfg.getAddress().getHost()));
            } catch (UnknownHostException e) {
                throw new RedisConnectionException("Unknown host", e);
            }
            log.debug("DNS monitoring enabled; Current master set to {}", currentMaster.get());
            monitorDnsChange(cfg);
        }
    }

    @Override
    protected void initEntry(MasterSlaveServersConfig config) {
        SingleEntry entry = new SingleEntry(0, MAX_SLOT, this, config);
        entry.setupMasterEntry(config.getMasterAddress().getHost(), config.getMasterAddress().getPort());
        entries.put(MAX_SLOT, entry);
    }

    private void monitorDnsChange(final SingleServerConfig cfg) {
        monitorFuture = GlobalEventExecutor.INSTANCE.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    InetAddress master = currentMaster.get();
                    InetAddress now = InetAddress.getByName(cfg.getAddress().getHost());
                    if (!now.getHostAddress().equals(master.getHostAddress())) {
                        log.info("Detected DNS change. {} has changed from {} to {}", cfg.getAddress().getHost(), master.getHostAddress(), now.getHostAddress());
                        if (currentMaster.compareAndSet(master, now)) {
                            changeMaster(MAX_SLOT,cfg.getAddress().getHost(), cfg.getAddress().getPort());
                            log.info("Master has been changed");
                        }
                    }

                } catch (Exception e) {
                    log.error(e.getMessage(), e);
                }

            }

        }, cfg.getDnsMonitoringInterval(), cfg.getDnsMonitoringInterval(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void shutdown() {
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
        }
        super.shutdown();
    }
}

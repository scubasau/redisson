package org.redisson;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.Assert;

public abstract class BaseConcurrentTest extends BaseTest {

    protected void testMultiInstanceConcurrency(int iterations, final RedissonRunnable runnable) throws InterruptedException {
        ExecutorService executor = Executors.newCachedThreadPool();

        final Map<Integer, Redisson> instances = new HashMap<Integer, Redisson>();
        for (int i = 0; i < iterations; i++) {
            instances.put(i, BaseTest.createInstance());
        }

        long watch = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            final int n = i;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    Redisson redisson = instances.get(n);
                    runnable.run(redisson);
                }
            });
        }

        executor.shutdown();
        Assert.assertTrue(executor.awaitTermination(5, TimeUnit.MINUTES));

        System.out.println("multi: " + (System.currentTimeMillis() - watch));

        executor = Executors.newCachedThreadPool();

        for (final Redisson redisson : instances.values()) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    redisson.shutdown();
                }
            });
        }

        executor.shutdown();
        Assert.assertTrue(executor.awaitTermination(5, TimeUnit.MINUTES));
    }

    protected void testSingleInstanceConcurrency(int iterations, final RedissonRunnable runnable) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

        final Redisson redisson = BaseTest.createInstance();
        long watch = System.currentTimeMillis();
        for (int i = 0; i < iterations; i++) {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    runnable.run(redisson);
                }
            });
        }

        executor.shutdown();
        Assert.assertTrue(executor.awaitTermination(5, TimeUnit.MINUTES));

        System.out.println(System.currentTimeMillis() - watch);

        redisson.shutdown();
    }



}

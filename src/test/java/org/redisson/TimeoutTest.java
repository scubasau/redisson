package org.redisson;

import io.netty.util.concurrent.Future;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.junit.Test;
import org.redisson.core.MessageListener;
import org.redisson.core.RMap;
import org.redisson.core.RTopic;

public class TimeoutTest extends BaseTest {

//    @Test
    public void testPubSub() throws InterruptedException, ExecutionException {
        RTopic<String> topic = redisson.getTopic("simple");
        topic.addListener(new MessageListener<String>() {
            @Override
            public void onMessage(String channel, String msg) {
                System.out.println("msg: " + msg);
            }
        });
        for (int i = 0; i < 100; i++) {
            Thread.sleep(1000);
            topic.publish("test" + i);
        }
    }


//    @Test
    public void testReplaceTimeout() throws InterruptedException, ExecutionException {
        RMap<Integer, Integer> map = redisson.getMap("simple");
        for (int i = 0; i < 10; i++) {
            map.put(i, i * 1000);
            map.replace(i, i * 1000 + 1);
            Thread.sleep(1000);
            System.out.println(i);
        }

        for (int i = 0; i < 10; i++) {
            Integer r = map.get(i);
            System.out.println(r);
        }
    }

//    @Test
    public void testPutAsyncTimeout() throws InterruptedException, ExecutionException {
        RMap<Integer, Integer> map = redisson.getMap("simple");
        List<Future<Integer>> futures = new ArrayList<Future<Integer>>();
        for (int i = 0; i < 10; i++) {
            Future<Integer> future = map.putAsync(i, i*1000);
            Thread.sleep(1000);
            futures.add(future);
            System.out.println(i);
        }

        for (Future<Integer> future : futures) {
            future.get();
        }

        for (int i = 0; i < 10; i++) {
            Integer r = map.get(i);
            System.out.println(r);
        }
    }

//    @Test
    public void testGetAsyncTimeout() throws InterruptedException, ExecutionException {
        RMap<Integer, Integer> map = redisson.getMap("simple");
        List<Future<Integer>> futures = new ArrayList<Future<Integer>>();
        for (int i = 0; i < 10; i++) {
            map.put(i, i*1000);
        }

        for (int i = 0; i < 10; i++) {
            Future<Integer> future = map.getAsync(i);
            Thread.sleep(1000);
            System.out.println(i);
            futures.add(future);
        }

        for (Future<Integer> future : futures) {
            Integer res = future.get();
            System.out.println(res);
        }

    }

}

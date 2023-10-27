package perf2;

import java.util.concurrent.SynchronousQueue;

public class RendezvousUsingSynchronousQueue {
    public static void test() throws Exception {
        long startTime = System.currentTimeMillis();
        final int max = 10_000_000;
        SynchronousQueue<Integer> data = new SynchronousQueue<>();

        Thread t1 = Thread.ofVirtual().start(() -> {
            int i = 0;
            while (i <= max) {
                try {
                    data.put(i);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                i += 1;
            }
        });

        Thread t2 = Thread.ofVirtual().start(() -> {
            long acc = 0L;
            for (int i = 0; i <= max; i++) {
                try {
                    acc += data.take();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }
            assert acc == sumUpTo(max);
        });

        t1.join();
        t2.join();

        long endTime = System.currentTimeMillis();
        System.out.println("Took: " + (endTime - startTime) + " ms");
    }

    public static long sumUpTo(int max) {
        return (long) max * (max + 1) / 2;
    }

    public static void main(String[] args) throws Exception {
        for (int i = 0; i < 10; i++) {
            test();
        }
    }
}

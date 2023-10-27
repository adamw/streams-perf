package perf2;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

// -Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1 -Djdk.virtualThreadScheduler.minRunnable=1
public class Rendezvous {
    private final int spinIterations;
    private final int yieldIterations;
    private final AtomicReference<ThreadAndCell> waiting = new AtomicReference<>();

    public Rendezvous(int spinIterations, int yieldIterations) {
        this.spinIterations = spinIterations;
        this.yieldIterations = yieldIterations;
    }

    public void test() throws Exception {
        long start = System.currentTimeMillis();

        final int max = 10_000_000;

        Thread t1 = Thread.ofPlatform().start(() -> {
            Thread ourThread = Thread.currentThread();

            for (int i = 0; i <= max; i++) {
                AtomicReference<Integer> ourCell = new AtomicReference<>(i);

                if (waiting.compareAndSet(null, new ThreadAndCell(ourThread, ourCell))) {
                    // CAS was successful, we are the first thread: parking and waiting for the data to be consumed
                    int doSpin = spinIterations;
                    int doYield = yieldIterations;
                    while (ourCell.get() != -1) {
                        if (doSpin > 0) {
                            Thread.onSpinWait();
                            doSpin -= 1;
                        } else if (doYield > 0) {
                            Thread.yield();
                            doYield -= 1;
                        } else {
                            LockSupport.park();
                        }
                    }
                } else {
                    // CAS was unsuccessful, there is already a thread waiting for us: clearing `waiting` for the
                    // next iteration, sending the data using the provided cell and unparking the other thread
                    ThreadAndCell other = waiting.get();
                    waiting.set(null);

                    other.cell.set(i);

                    LockSupport.unpark(other.thread);
                }
            }
        });

        Thread t2 = Thread.ofPlatform().start(() -> {
            long acc = 0L;
            Thread ourThread = Thread.currentThread();

            for (int i = 0; i <= max; i++) {
                AtomicReference<Integer> ourCell = new AtomicReference<>(-1); // -1 -> no data provided yet
                if (waiting.compareAndSet(null, new ThreadAndCell(ourThread, ourCell))) {
                    // CAS was successful, we are the first thread: parking and waiting for the data to be provided
                    int doSpin = spinIterations;
                    int doYield = yieldIterations;
                    while (ourCell.get() == -1) {
                        if (doSpin > 0) {
                            Thread.onSpinWait();
                            doSpin -= 1;
                        } else if (doYield > 0) {
                            Thread.yield();
                            doYield -= 1;
                        } else {
                            LockSupport.park();
                        }
                    }
                    acc += ourCell.get();
                } else {
                    // CAS was unsuccessful, there is already a thread waiting for us: clearing `waiting` for the
                    // next iteration, consuming the data and unparking the other thread
                    ThreadAndCell other = waiting.get();
                    waiting.set(null);

                    acc += other.cell.get();
                    other.cell.set(-1);

                    LockSupport.unpark(other.thread);
                }
            }

            assert acc == sumUpTo(max);
        });

        t1.join();
        t2.join();

        long end = System.currentTimeMillis();
        System.out.println("Took (spin=" + spinIterations + ", yield=" + yieldIterations + "): " + (end - start) + " ms");
    }

    private long sumUpTo(int max) {
        return ((long) max * (max + 1)) / 2;
    }

    private record ThreadAndCell(Thread thread, AtomicReference<Integer> cell) {}

    public static void main(String[] args) throws Exception {
        for (int i=0; i<10; i++) {
            new Rendezvous(1, 0).test();
//            new Rendezvous(10, 0).test();
//            new Rendezvous(100, 0).test();
            new Rendezvous(1000, 0).test();
            new Rendezvous(10000, 0).test();
//            new Rendezvous(0, 0).test();
//            new Rendezvous(1000, 0).test();
//            new Rendezvous(5000, 0).test();
//            new Rendezvous(10000, 0).test();
//            new Rendezvous(100000, 0).test();
//
//            new Rendezvous(0, 1).test();
//            new Rendezvous(0, 2).test();
//            new Rendezvous(0, 3).test();
//            new Rendezvous(0, 4).test();
//            new Rendezvous(0, 8).test();
//
//            new Rendezvous(10000, 1).test();
//            new Rendezvous(10000, 4).test();

            System.out.println("");
        }
    }
}

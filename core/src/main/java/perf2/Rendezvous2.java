package perf2;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

// -Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1 -Djdk.virtualThreadScheduler.minRunnable=1
// with changes inspired by Exchanger
public class Rendezvous2 {
    private final AtomicReference<ThreadAndCell> waiting = new AtomicReference<>();

    private final boolean doSpinWait;

    public Rendezvous2(boolean doSpinWait) {
        this.doSpinWait = doSpinWait;
    }

    private static final int SPINS = 1 << 10;
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    public void test() throws Exception {
        long start = System.currentTimeMillis();

        final int max = 10_000_000;

        Thread t1 = Thread.ofPlatform().start(() -> {
            Thread ourThread = Thread.currentThread();

            for (int i = 0; i <= max; i++) {
                AtomicReference<Integer> ourCell = new AtomicReference<>(i);

                if (waiting.compareAndSet(null, new ThreadAndCell(ourThread, ourCell))) {
                    // CAS was successful, we are the first thread: parking and waiting for the data to be consumed
                    int spins = SPINS;
                    int h = 0;
                    Thread t = Thread.currentThread();
                    while (ourCell.get() != -1) {
                        if (spins > 0) {
                            h ^= h << 1; h ^= h >>> 3; h ^= h << 10; // xorshift
                            if (h == 0) {                // initialize hash
                                h = SPINS | (int) t.threadId();
                                if (doSpinWait) Thread.onSpinWait();
                            } else if (h < 0 &&          // approx 50% true
                                    (--spins & ((SPINS >>> 1) - 1)) == 0) {
                                Thread.yield();        // two yields per wait
                            } else {
                                if (doSpinWait) Thread.onSpinWait();
                            }
                        } else {
                            LockSupport.park(t);
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
                    int spins = SPINS;
                    int h = 0;
                    Thread t = Thread.currentThread();
                    while (ourCell.get() == -1) {
                        if (spins > 0) {
                            h ^= h << 1; h ^= h >>> 3; h ^= h << 10; // xorshift
                            if (h == 0) {                // initialize hash
                                h = SPINS | (int) t.threadId();
                                if (doSpinWait) Thread.onSpinWait();
                            } else if (h < 0 &&          // approx 50% true
                                    (--spins & ((SPINS >>> 1) - 1)) == 0) {
                                Thread.yield();        // two yields per wait
                            } else {
                                if (doSpinWait) Thread.onSpinWait();
                            }
                        } else {
                            LockSupport.park(t);
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
        System.out.println("Took (v2, spinWait=" + doSpinWait + "): " + (end - start) + " ms");
    }

    private long sumUpTo(int max) {
        return ((long) max * (max + 1)) / 2;
    }

    private record ThreadAndCell(Thread thread, AtomicReference<Integer> cell) {}
}

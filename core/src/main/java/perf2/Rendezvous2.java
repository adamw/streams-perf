package perf2;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Exchanger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

// -Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1 -Djdk.virtualThreadScheduler.minRunnable=1
// with changes inspired by Exchanger
public class Rendezvous2 {
    private volatile Thread waiting;
    private volatile int data = -1; // together with `consumed`, used to transmit data if t1 wins the race (and waits for t2)
    private volatile boolean consumed = false;
    private volatile int data2 = -1; // used to transmit data if t2 wins the race (and waits for t1)

    private final boolean doSpinWait;

    public Rendezvous2(boolean doSpinWait) {
        this.doSpinWait = doSpinWait;
    }

    private static final int SPINS = 1 << 10;
    private static final int NCPU = Runtime.getRuntime().availableProcessors();

    // VarHandle mechanics
    private static final VarHandle WAITING;
    static {
        try {
            MethodHandles.Lookup l = MethodHandles.lookup();
            WAITING = l.findVarHandle(Rendezvous2.class, "waiting", Thread.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    public void test() throws Exception {
        long start = System.currentTimeMillis();

        final int max = 10_000_000;

        Thread t1 = Thread.ofVirtual().start(() -> {
            Thread ourThread = Thread.currentThread();

            for (int i = 0; i <= max; i++) {
                data = i;
                if (WAITING.compareAndSet(Rendezvous2.this, null, ourThread)) {
                    // CAS was successful, we are the first thread: parking and waiting for the already set
                    // `data` to be `consumed`
                    int spins = SPINS;
                    int h = 0;
                    while (!consumed) {
                        if (spins > 0) {
                            h ^= h << 1; h ^= h >>> 3; h ^= h << 10; // xorshift
                            if (h == 0) {                // initialize hash
                                h = SPINS | (int) ourThread.threadId();
                                if (doSpinWait) Thread.onSpinWait();
                            } else if (h < 0 &&          // approx 50% true
                                    (--spins & ((SPINS >>> 1) - 1)) == 0) {
                                Thread.yield();        // two yields per wait
                            } else {
                                if (doSpinWait) Thread.onSpinWait();
                            }
                        } else {
                            LockSupport.park(ourThread);
                        }
                    }
                    // resetting for the next iteration
                    consumed = false;
                } else {
                    // CAS was unsuccessful, there is already a thread waiting for us: clearing `waiting` for the
                    // next iteration, sending the data using `data2` and unparking the other thread
                    Thread other = waiting;

                    waiting = null;
                    data2 = i;

                    LockSupport.unpark(other);
                }
            }
        });

        Thread t2 = Thread.ofVirtual().start(() -> {
            long acc = 0L;
            Thread ourThread = Thread.currentThread();

            for (int i = 0; i <= max; i++) {
                if (WAITING.compareAndSet(Rendezvous2.this, null, ourThread)) {
                    // CAS was successful, we are the first thread: parking and waiting for the data
                    // to be provided in `data2`
                    int spins = SPINS;
                    int h = 0;
                    while (data2 == -1) {
                        if (spins > 0) {
                            h ^= h << 1; h ^= h >>> 3; h ^= h << 10; // xorshift
                            if (h == 0) {                // initialize hash
                                h = SPINS | (int) ourThread.threadId();
                                if (doSpinWait) Thread.onSpinWait();
                            } else if (h < 0 &&          // approx 50% true
                                    (--spins & ((SPINS >>> 1) - 1)) == 0) {
                                Thread.yield();        // two yields per wait
                            } else {
                                if (doSpinWait) Thread.onSpinWait();
                            }
                        } else {
                            LockSupport.park(ourThread);
                        }
                    }

                    acc += data2;
                    data2 = -1; // resetting for the next iteration, if we end up in the same branch
                } else {
                    // CAS was unsuccessful, there is already a thread waiting for us: clearing `waiting` for the
                    // next iteration, consuming the data & singalling through `consumed, finally unparking the
                    // other thread
                    Thread other = waiting;

                    acc += data;
                    waiting = null;
                    consumed = true;

                    LockSupport.unpark(other);
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
}

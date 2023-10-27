package perf

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

// max times rendezvous using a sync queue
def rendezvousUsingSynchronousQueue(): Unit =
  timed("SynchronousQueue") {
    val max = 10_000_000
    val data = new SynchronousQueue[Int]()

    val t1 = Thread
      .ofVirtual()
      .start(() =>
        var i = 0
        while i <= max do
          data.put(i)
          i += 1
      )

    val t2 = Thread
      .ofVirtual()
      .start(() =>
        var acc = 0L
        for (i <- 0 to max)
          acc += data.take

        assert(acc == sumUpTo(max))
      )

    t1.join()
    t2.join()
  }

// max times rendezvous using a lock support to synchronise threads - slow, an atomic ref, a volatile var + spin wait to complete pairing
def rendezvousUsingLockSupport(): Unit =
  @volatile var data: Int = -1
  val waiting = new AtomicReference[Thread]()

  timed("LockSupport") {
    val max = 1_000_000

    val t1 = Thread
      .ofVirtual()
      .start(() =>
        for (i <- 0 to max)
          if waiting.compareAndSet(null, Thread.currentThread()) then
            data = i // data to transfer
            // we're waiting for the other thread to take the data
            while data != -1 do LockSupport.park()
            // other thread consumed the data & (possibly) unparked us; even if the unpark hasn't been consumed, it will be on the next iteration
          else
            data = i // data to transfer
            val other = waiting.get(); waiting.set(null)
            LockSupport.unpark(other)
            while data != -1 do Thread.onSpinWait() // wait for the other thread to consume the data
      )

    val t2 = Thread
      .ofVirtual()
      .start(() =>
        var acc = 0L
        for (i <- 0 to max)
          if waiting.compareAndSet(null, Thread.currentThread()) then
            while data == -1 do LockSupport.park()
            acc += data; data = -1
          else
            // other thread won, it's parking - the `data` must be set; using & resetting it; also resetting `waiting` and unparking
            while data == -1 do Thread.onSpinWait()
            val other = waiting.get(); waiting.set(null)
            acc += data; data = -1
            LockSupport.unpark(other)

        assert(acc == sumUpTo(max))
      )

    t1.join()
    t2.join()
  }

// same as above, but first iteration yields, doesn't block
def rendezvousUsingLockSupport2(): Unit =
  @volatile var data: Int = -1
  val waiting = new AtomicReference[Thread]()

  timed("LockSupport2") {
    val max = 1_000_000

    val t1 = Thread
      .ofVirtual()
      .start(() =>
        for (i <- 0 to max)
          if waiting.compareAndSet(null, Thread.currentThread()) then
            var x = false
            data = i // data to transfer
            // we're waiting for the other thread to take the data
            while data != -1 do
              if x then LockSupport.park()
              else
                x = true
                Thread.`yield`()
          // other thread consumed the data & (possibly) unparked us; even if the unpark hasn't been consumed, it will be on the next iteration
          else
            data = i // data to transfer
            val other = waiting.get(); waiting.set(null)
            LockSupport.unpark(other)
            while data != -1 do Thread.onSpinWait() // wait for the other thread to consume the data
      )

    val t2 = Thread
      .ofVirtual()
      .start(() =>
        var acc = 0L
        for (i <- 0 to max)
          if waiting.compareAndSet(null, Thread.currentThread()) then
            var x = false

            while data == -1 do
              if x then LockSupport.park()
              else
                x = true
                Thread.`yield`()

            acc += data; data = -1
          else
            // other thread won, it's parking - the `data` must be set; using & resetting it; also resetting `waiting` and unparking
            while data == -1 do Thread.onSpinWait()
            val other = waiting.get(); waiting.set(null)
            acc += data; data = -1
            LockSupport.unpark(other)

        assert(acc == sumUpTo(max))
      )

    t1.join()
    t2.join()
  }

// max times rendezvous using a lock support to synchronise threads - better perf, passing an atomic ref in an atomic ref, first iteration yields 
def rendezvousUsingLockSupport3(firstYield: Boolean): Unit =
  val waiting = new AtomicReference[(Thread, AtomicReference[Int])]()

  timed(s"LockSupport3(yield=$firstYield)") {
    val max = 10_000_000

    val t1 = Thread
      .ofVirtual()
      .start(() =>
        val ourThread = Thread.currentThread()

        for (i <- 0 to max)
          val ourCell = new AtomicReference[Int](i)

          if waiting.compareAndSet(null, (ourThread, ourCell)) then
            var tryYield = firstYield
            while ourCell.get() != -1 do // -1 -> consumed
              if tryYield then
                Thread.`yield`()
                tryYield = false
              else LockSupport.park()
          else
            val (otherThread, otherCell) = waiting.get()
            waiting.set(null)

            otherCell.set(i)

            LockSupport.unpark(otherThread)
      )

    val t2 = Thread
      .ofVirtual()
      .start(() =>
        var acc = 0L
        val ourThread = Thread.currentThread()

        for (i <- 0 to max)
          val ourCell = new AtomicReference[Int](-1)
          if waiting.compareAndSet(null, (ourThread, ourCell)) then
            var tryYield = firstYield
            while ourCell.get() == -1 do
              if tryYield then
                Thread.`yield`()
                tryYield = false
              else LockSupport.park()
            acc += ourCell.get()
          else
            val (otherThread, otherCell) = waiting.get()
            waiting.set(null)

            acc += otherCell.get()
            otherCell.set(-1)

            LockSupport.unpark(otherThread)

        assert(acc == sumUpTo(max))
      )

    t1.join()
    t2.join()
  }

// max times rendezvous using monix & mvar-in-mvar (similar to atomic refs above)
def rendezvousUsingMonix(): Unit =
  val max = 10_000_000

  timed("cats-effect") {
    import monix.eval.Task
    import monix.catnap.MVar

    def p1(i: Int, q: MVar[Task, MVar[Task, Int]]): Task[Unit] =
      if i > max then Task.unit
      else
        MVar.of[Task, Int](i).flatMap { c =>
          q.tryPut(c).flatMap {
            case true =>
              // wait until other side consumes
              c.put(-1).flatMap(_ => p1(i + 1, q))
            case false =>
              q.take.flatMap(c => c.put(i).flatMap(_ => c.put(-1).flatMap(_ => p1(i + 1, q))))
          }
        }

    def p2(i: Int, q: MVar[Task, MVar[Task, Int]], acc: Long): Task[Unit] =
      if i > max then Task.eval(assert(acc == sumUpTo(max)))
      else
        MVar.empty[Task, Int]().flatMap { c =>
          q.tryPut(c).flatMap {
            case true =>
              c.take.flatMap(v => p2(i+1, q, acc + v))
            case false =>
              q.take.flatMap(c => c.take.flatMap(v => p2(i+1, q, acc + v)))
          }
        }

    val app = for {
      q <- MVar.empty[Task, MVar[Task, Int]]()
      f1 <- p1(0, q).start
      f2 <- p2(0, q, 0).start
      _ <- f1.join
      _ <- f2.join
    } yield ()

    import monix.execution.Scheduler.Implicits.global
    app.runSyncUnsafe()
  }

package perf

import akka.actor.ActorSystem
import monix.eval.Task
import monix.reactive.Observable
import org.jctools.queues.MpmcArrayQueue
import ox.*
import ox.channels.*

import java.util.concurrent.ArrayBlockingQueue
import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration.*

// sum of elements in the channel: 1..max
def usingOx(capacity: Int): Unit =
  val max = 100_000

  timed(s"ox($capacity)") {
    supervised {
      given StageCapacity = StageCapacity(capacity)

      val source: Source[Int] = Source.range(0, max, 1)

      @tailrec
      def runFold(acc: Long): Long =
        source.receive() match
          case ChannelClosed.Done     => acc
          case e: ChannelClosed.Error => throw e.toThrowable
          case n: Int                 => runFold(acc + n)

      assert(runFold(0L) == sumUpTo(max))
    }
  }

// sum of elements in the channel: 1..max, using clauses
def usingOx2(capacity: Int): Unit =
  val max = 100_000

  timed(s"ox2($capacity)") {
    supervised {
      given StageCapacity = StageCapacity(capacity)

      val exc = StageCapacity.newChannel[Int]
      val source: Source[Int] = Source.range(0, max, 1)

      @tailrec
      def runFold(acc: Long): Long =
        select(exc.receiveClause, source.receiveOrDoneClause) match
          case ChannelClosed.Done     => acc
          case e: ChannelClosed.Error => throw e.toThrowable
          case source.Received(n)     => runFold(acc + n)

      assert(runFold(0L) == sumUpTo(max))
    }
  }

// sum of elements in a blocking queue: 1..max
def usingBlockingQueue(capacity: Int): Unit =
  val max = 10_000_000

  timed(s"queue($capacity)") {
    supervised {
      val q = new ArrayBlockingQueue[Long | ChannelClosed.Done.type](capacity)

      fork {
        for (i <- 0 until max + 1) q.put(i)
        q.put(ChannelClosed.Done)
      }

      @tailrec
      def runFold(acc: Long): Long =
        q.take() match
          case ChannelClosed.Done => acc
          case n: Long            => runFold(acc + n)

      assert(runFold(0L) == sumUpTo(max))
    }
  }

// sum of elements 1..max using an akka stream
implicit val as: ActorSystem = ActorSystem()
def usingAkka(): Unit =
  timed("akka") {
    val max = 10_000_000

    val source = akka.stream.scaladsl.Source.unfold(0)(n => if (n > max) None else Some((n + 1, n)))

    val consumeWithFold = Await.result(source.runFold(0L)(_ + _), 10.seconds)

    assert(consumeWithFold == sumUpTo(max))
  }

// sum of elements 1..max using monix
def usingMonix(): Unit =
  import monix.execution.Scheduler.Implicits.global
  timed("monix") {
    val max = 10_000_000

    val source: Observable[Long] = Observable.range(from = 0, until = max + 1, step = 1)

    val consumeWithFold: Task[Long] = source.foldLeftL(0L) { case (sum, elem) => elem + sum }

    assert(consumeWithFold.runSyncUnsafe() == sumUpTo(max))
  }

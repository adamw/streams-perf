package perf

import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.effect.unsafe.implicits.global

import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

// max times rendezvous using cats-effect's synchronous queue
def rendezvousUsingCatsEffect(): Unit =
  val max = 10_000_000

  timed("cats-effect") {
    def p1(i: Int, q: Queue[IO, Int]): IO[Unit] =
      if i > max then IO.unit
      else q.offer(i).flatMap(_ => p1(i + 1, q))

    def p2(i: Int, q: Queue[IO, Int], acc: Long): IO[Unit] =
      if i > max then IO(assert(acc == sumUpTo(max)))
      else q.take.flatMap(_ => p2(i + 1, q, acc + i))

    val _ec = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutor(_ec)
    val app = for {
      q <- Queue.synchronous[IO, Int]
      f1 <- p1(0, q).startOn(ec)
      f2 <- p2(0, q, 0).startOn(ec)
      _ <- f1.joinWithUnit
      _ <- f2.joinWithUnit
      _ <- IO(_ec.shutdown())
    } yield ()

    app.unsafeRunSync()
  }

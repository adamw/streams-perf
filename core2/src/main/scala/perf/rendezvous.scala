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
      else q.take.flatMap(j => p2(i + 1, q, acc + j))

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

def rendezvousUsingCatsEffect2(): Unit =
  val max = 10_000_000

  timed("cats-effect2") {
    def p1(i: Int, q: Ref[IO, Option[(Long, Deferred[IO, Long])]]): IO[Unit] =
      if i > max then IO.unit
      else
        Deferred[IO, Long].flatMap { done =>
          def race(): IO[Unit] = q
            .tryModify {
              case None             => (Some((i, done)), None) // we won the race, we'll be waiting
              case Some((_, other)) => (Some((-1, other)), Some(other))
            }
            .flatMap {
              case None =>
                // retry
                race()
              case Some(None) =>
                // we have to wait for the other fiber
                done.get >> p1(i + 1, q)
              case Some(Some(other)) =>
                // the other fiber is waiting for data - resetting the ref, resuming the partner
                q.set(None) >> other.complete(i) >> p1(i + 1, q)
            }

          race()
        }

    def p2(i: Int, q: Ref[IO, Option[(Long, Deferred[IO, Long])]], acc: Long): IO[Unit] =
      if i > max then IO(assert(acc == sumUpTo(max)))
      else
        Deferred[IO, Long].flatMap { done =>
          def race(): IO[Unit] = q
            .tryModify {
              case None             => (Some((-1, done)), None) // we won the race, we'll be waiting
              case Some((j, other)) => (Some((j, other)), Some((j, other)))
            }
            .flatMap {
              case None =>
                // retry
                race()
              case Some(None) =>
                // we have to wait for the other fiber
                done.get.flatMap(j => p2(i + 1, q, acc + j))
              case Some(Some((j, other))) =>
                // the other fiber is waiting - resetting the ref, resuming the partner
                q.set(None) >> other.complete(-1) >> p2(i + 1, q, acc + j)
            }

          race()
        }

    val _ec = Executors.newSingleThreadExecutor()
    val ec = ExecutionContext.fromExecutor(_ec)
    val app = for {
      q <- Ref[IO].of[Option[(Long, Deferred[IO, Long])]](None)
      f1 <- p1(0, q).start // .startOn(ec)
      f2 <- p2(0, q, 0).start // .startOn(ec)
      _ <- f1.joinWithUnit
      _ <- f2.joinWithUnit
      _ <- IO(_ec.shutdown())
    } yield ()

    app.unsafeRunSync()
  }

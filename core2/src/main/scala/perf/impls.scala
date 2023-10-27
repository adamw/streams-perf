package perf

import cats.effect.IO

// sum of elements 1..max using fs2
def usingFs2(): Unit =
  timed("fs2") {
    val max = 10_000_000
    val source = fs2.Stream.range(start = 0, stopExclusive = max + 1, step = 1)
    val consumeWithFold = source.compile.fold(0L) { case (sum, elem) => sum + elem }
    assert(consumeWithFold == sumUpTo(max))
  }

// sum of elements 1..max using fs2, where each operation is async
def usingFs2Async(): Unit =
  import cats.effect.unsafe.implicits.global
  timed("fs2") {
    val max = 10_000_000
    val source = fs2.Stream.iterateEval(1L)(i => IO.pure(i + 1)).take(max)
    val consumeWithFold = source.compile.fold(0L) { case (sum, elem) => sum + elem }
    assert(consumeWithFold.unsafeRunSync() == sumUpTo(max))
  }

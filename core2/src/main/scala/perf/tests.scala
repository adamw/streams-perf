package perf

@main def run(): Unit =
  for (i <- 1 to 3) {
    println(s"Run $i")
//    usingFs2()
//    usingFs2Async()
    rendezvousUsingCatsEffect()
  }

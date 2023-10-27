package perf

@main def run(): Unit =
  for (i <- 1 to 10) {
    println(s"Run $i")
//    usingFs2()
//    usingFs2Async()
//    rendezvousUsingCatsEffect()
    rendezvousUsingCatsEffect2()
  }

package perf

@main def run(): Unit =
  try
    for (i <- 1 to 10) {
      println(s"Run $i")
//      usingOx(0)
//      usingOx(1)
//      usingOx(100)
//      usingBlockingQueue(1)
//      usingBlockingQueue(100)
//      usingAkka()
//      usingMonix()

      //      rendezvousUsingLockSupport()
      //      rendezvousUsingLockSupport2()

//      rendezvousUsingSynchronousQueue()

      // both faster with -Djdk.virtualThreadScheduler.parallelism=1 -Djdk.virtualThreadScheduler.maxPoolSize=1 -Djdk.virtualThreadScheduler.minRunnable=1
//      rendezvousUsingLockSupport3(true)
//      rendezvousUsingLockSupport3(false)

//      rendezvousUsingMonix()
    }
  finally as.terminate()

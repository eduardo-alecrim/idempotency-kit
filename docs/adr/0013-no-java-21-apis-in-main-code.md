# No Java 21 APIs in main code

Main code compiles with `--release 17`: records and sealed types, but no pattern matching for `switch` and no virtual-thread APIs. Users on Java 17 are the long tail. Virtual-thread friendliness is a property of the code (no `synchronized` around I/O, `ReentrantLock` where a lock is needed) and is verified on Java 21 and 25 in CI.

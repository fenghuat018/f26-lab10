# RACE.md

One section per milestone. Fill each one in order as you go.

The prediction in milestone 1 is written BEFORE you run your race test. A TA
may ask about it. Commit it on its own so the order is visible.

Keep it short and specific. Point at methods, call sites, and the invariant.

---

## Milestone 1: from the ticket to a test that fails every time

### Suspect list

Everything your review turned up that touches shared state, with a one-line
verdict for each. Add or remove rows as needed.

| # | What you found (method, field, or class) | Verdict, in one line |
|---|---|---|
| 1 | `BookingService.book()`: `hasOverlap()` followed by `insert()` | Explains the ticket: each store call is atomic, but another thread can insert between the check and the insert, allowing overlapping confirmations. |
| 2 | `InMemoryBookingStore.bookings`, `hasOverlap()`, `insert()`, and `size()` | The private list is protected by the same store monitor for each operation, but `insert()` does not check for conflicts or make the service's check-and-insert atomic. |
| 3 | `InMemoryBookingStore.cancelAndPromote()` and its helpers | Cancellation, conflict checking, and promotion run under one store lock, so there is no observable gap between cancellation and promotion. |
| 4 | `BookingService.joinWaitlist()` | Inserts shared booking data, but a `WAITLISTED` entry holds no minutes and does not itself violate the confirmed-booking invariant. |
| 5 | `BookingService.sequence` / `nextId()` | `AtomicLong.incrementAndGet()` is safe within one service; separate service instances have separate sequences, but that ID-uniqueness limitation does not explain the ticket's distinct-ID overlapping confirmations. |
| 6 | `BookingStats` counters | Unsynchronized increments can lose updates and reads can be stale, but approximate counts are intentional and never drive booking decisions, so they cannot explain duplicate confirmations. |

**Which one explains the ticket, and how you know.** Tie it to the invariant
the ticket names.

`BookingService.book()` explains the ticket. The invariant is that a room may
have at most one `CONFIRMED` booking covering any given minute. Its
`hasOverlap()` call and subsequent `insert()` are individually synchronized
by the store, but the pair is not atomic. Two callers can both observe no
overlap and then insert different confirmed bookings for the same room and
range, producing both the invalid stored state and the two confirmation logs.

### Predicted interleaving (write this before you run your race test)

Two threads, one row per step, in the form from lecture. Describe what each
thread does at that step and leave the other column empty. Add or remove rows
as your prediction needs.

| Step | Thread A | Thread B |
|---|---|---|
| t1 | Calls `book("WEH-5409", 900, 960)` on an empty store; the real `hasOverlap()` returns `false`, and `PausingStore` pauses before returning that value to the service. | |
| t2 | | Calls `book("WEH-5409", 900, 960)`; `hasOverlap()` also returns `false`. |
| t3 | | Inserts its own `CONFIRMED` booking, logs the confirmation, and completes. |
| t4 | After the test releases the gate, receives the previously computed `false`. | |
| t5 | Does not recheck; inserts its own `CONFIRMED` booking, logs the confirmation, and completes. | |
| | **End state:** Two distinct `CONFIRMED` bookings cover `WEH-5409 [900, 960)`, violating the invariant. | |

Planned aim: `new PausingStore(real, "hasOverlap", 1)`. The first overlap
check computes `false` before pausing, after the real store's lock has been
released. The test waits for A to pause, starts B and waits for it to finish,
then releases A. The gate fires only once, so B is not paused. A's saved
answer remains `false` even after B's insertion.

If the run turned out different from your prediction, add one line on what
you missed. Leave the table as you first wrote it.

### Your race test

**How you aimed `PausingStore`, and the result.** Method name and call index, why
that pair pins the schedule you predicted, and the output of two separate
runs (assertion failure and log lines).


Implemented in `RaceTest.overlappingRequestsConfirmAtMostOneBooking()` with
`new PausingStore(real, "hasOverlap", 1)`. Both customers use the same service,
room (`WEH-5409`), and range (`[900, 960)`). The first real overlap check
computes `false` and releases the store monitor before the wrapper pauses A.
Only that first call pauses, so B can check the still-empty slot and insert
its confirmed booking before the test releases A. A then inserts based on
its saved `false` answer.

The test uses bounded waits and joins, releases the gate in `finally`, and
checks that both workers completed without exceptions. Its regression
assertion counts overlapping `CONFIRMED` bookings directly in `real` and
expects exactly one; it does not assert that the buggy end state is correct.
Gate and scheduling diagnostics are printed rather than used as regression
assertions, so later fixes can change the internal call sequence.

**Prediction versus observation:** Both runs match the prediction. Each
prints `paused=true fired=true targetCalls=2` and
`customerBFinishedBeforeRelease=true`, followed by an invariant assertion
failure (`expected: <1> but was: <2>`). Each has two `booking.confirmed` log
lines with distinct IDs for the same room and range. There were no worker
exceptions or unfinished-worker assertion failures. The original prediction
above is unchanged.

Both runs used the following command in separate Maven invocations and each
exited with status 1. Full combined standard output and standard error follow.

```bash
mvn -B -Dstyle.color=never -Dtest=RaceTest test
```

#### Run 1 (exit status 1)

```text
[INFO] Scanning for projects...
[INFO] 
[INFO] ------------------< edu.cmu.cs214:lab10-reservations >------------------
[INFO] Building lab10-reservations 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- resources:3.4.0:resources (default-resources) @ lab10-reservations ---
[INFO] skip non existing resourceDirectory /Users/tongfenghua/My mac/CMU-Learning/Agent Eng/f26-lab10/src/main/resources
[INFO] 
[INFO] --- compiler:3.13.0:compile (default-compile) @ lab10-reservations ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] 
[INFO] --- resources:3.4.0:testResources (default-testResources) @ lab10-reservations ---
[INFO] skip non existing resourceDirectory /Users/tongfenghua/My mac/CMU-Learning/Agent Eng/f26-lab10/src/test/resources
[INFO] 
[INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ lab10-reservations ---
[INFO] Recompiling the module because of changed source code.
[INFO] Compiling 3 source files with javac [debug release 21] to target/test-classes
[INFO] 
[INFO] --- surefire:3.2.5:test (default-test) @ lab10-reservations ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO] 
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running edu.cmu.cs214.lab10.harness.RaceTest
2026-11-06T00:34:32.007Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T00:34:32.010Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
race aim=hasOverlap call=1 paused=true fired=true targetCalls=2 customerBFinishedBeforeRelease=true
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.047 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.overlappingRequestsConfirmAtMostOneBooking -- Time elapsed: 0.031 s <<< FAILURE!
org.opentest4j.AssertionFailedError: A room must have exactly one confirmed booking after two requests for the same free slot ==> expected: <1> but was: <2>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:166)
	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:664)
	at edu.cmu.cs214.lab10.harness.RaceTest.overlappingRequestsConfirmAtMostOneBooking(RaceTest.java:69)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)

[INFO] 
[INFO] Results:
[INFO] 
[ERROR] Failures: 
[ERROR]   RaceTest.overlappingRequestsConfirmAtMostOneBooking:69 A room must have exactly one confirmed booking after two requests for the same free slot ==> expected: <1> but was: <2>
[INFO] 
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  1.699 s
[INFO] Finished at: 2026-11-05T19:34:32-05:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project lab10-reservations: There are test failures.
[ERROR] 
[ERROR] Please refer to /Users/tongfenghua/My mac/CMU-Learning/Agent Eng/f26-lab10/target/surefire-reports for the individual test results.
[ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```

#### Run 2 (exit status 1)

```text
[INFO] Scanning for projects...
[INFO] 
[INFO] ------------------< edu.cmu.cs214:lab10-reservations >------------------
[INFO] Building lab10-reservations 1.0.0
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- resources:3.4.0:resources (default-resources) @ lab10-reservations ---
[INFO] skip non existing resourceDirectory /Users/tongfenghua/My mac/CMU-Learning/Agent Eng/f26-lab10/src/main/resources
[INFO] 
[INFO] --- compiler:3.13.0:compile (default-compile) @ lab10-reservations ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] 
[INFO] --- resources:3.4.0:testResources (default-testResources) @ lab10-reservations ---
[INFO] skip non existing resourceDirectory /Users/tongfenghua/My mac/CMU-Learning/Agent Eng/f26-lab10/src/test/resources
[INFO] 
[INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ lab10-reservations ---
[INFO] Nothing to compile - all classes are up to date.
[INFO] 
[INFO] --- surefire:3.2.5:test (default-test) @ lab10-reservations ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO] 
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running edu.cmu.cs214.lab10.harness.RaceTest
2026-11-06T00:34:38.095Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T00:34:38.098Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
race aim=hasOverlap call=1 paused=true fired=true targetCalls=2 customerBFinishedBeforeRelease=true
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.046 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.overlappingRequestsConfirmAtMostOneBooking -- Time elapsed: 0.030 s <<< FAILURE!
org.opentest4j.AssertionFailedError: A room must have exactly one confirmed booking after two requests for the same free slot ==> expected: <1> but was: <2>
	at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
	at org.junit.jupiter.api.AssertionFailureBuilder.buildAndThrow(AssertionFailureBuilder.java:132)
	at org.junit.jupiter.api.AssertEquals.failNotEqual(AssertEquals.java:197)
	at org.junit.jupiter.api.AssertEquals.assertEquals(AssertEquals.java:166)
	at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:664)
	at edu.cmu.cs214.lab10.harness.RaceTest.overlappingRequestsConfirmAtMostOneBooking(RaceTest.java:69)
	at java.base/java.lang.reflect.Method.invoke(Method.java:565)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
	at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)

[INFO] 
[INFO] Results:
[INFO] 
[ERROR] Failures: 
[ERROR]   RaceTest.overlappingRequestsConfirmAtMostOneBooking:69 A room must have exactly one confirmed booking after two requests for the same free slot ==> expected: <1> but was: <2>
[INFO] 
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  0.994 s
[INFO] Finished at: 2026-11-05T19:34:38-05:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test (default-test) on project lab10-reservations: There are test failures.
[ERROR] 
[ERROR] Please refer to /Users/tongfenghua/My mac/CMU-Learning/Agent Eng/f26-lab10/target/surefire-reports for the individual test results.
[ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
```

---

## Milestone 2: the patch a teammate sent

**Your milestone-1 test on the patched code.** Green or red. Paste the result.

**The re-aimed run.** Method name, call index, the pasted result, and one line
on why that aim tests the patch rather than sidestepping it.

**Your verdict.** Accept or reject, and what your milestone-1 result does and
does not show about the patch.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Name the lock or the atomic operation
and the two operations it now covers. Paste the diff of your change and the
passing run of your milestone-1 test and the full suite.

**Is your milestone-1 test still testing anything?** After your fix, describe
what that test now exercises. Did `PausingStore` pause a call during the green run? How do
you know?

**What runs one at a time now that ran in parallel before.** Name concretely which callers
queue behind which. "Nothing, it is just correct now" does not count as an
answer.

**What you left unfixed.** For anything your review flagged that you chose not
to fix, one line each on why.

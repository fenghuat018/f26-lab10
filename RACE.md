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

Green with the original aim, `new PausingStore(real, "hasOverlap", 1)`.
The following output was supplied from the user's run on the patched code;
it was not rerun while recording this result.

A pauses after its first overlap check computes `false`. B then completes
both checks and inserts a confirmed booking. After A resumes, the patch's
second check detects B's booking and rejects A. The output shows one
confirmation, one rejection, four overlap calls, and a passing invariant
assertion. This demonstrates that the added check prevents the original
interleaving from producing a double booking; it does not establish that
the second check and insertion are atomic or rule out other interleavings.

```text
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running edu.cmu.cs214.lab10.harness.RaceTest
2026-11-06T01:08:07.409Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T01:08:07.412Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
race aim=hasOverlap call=1 paused=true fired=true targetCalls=4 customerBFinishedBeforeRelease=true
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.040 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  1.695 s
[INFO] Finished at: 2026-11-05T20:08:07-05:00
```

**The re-aimed run.** Method name, call index, the pasted result, and one line
on why that aim tests the patch rather than sidestepping it.

Red with `new PausingStore(real, "hasOverlap", 2)`. Since B starts only
once A pauses, the second call across all threads is A's added defensive
re-check. Both checks execute normally; pausing after the added check
computes `false` exposes the gap between that check and `insert()`, directly
testing the patch's claimed protection.

B completes its two checks and inserts `bk-2` while A is paused. A then
resumes with its saved `false` and inserts `bk-1`. The diagnostics show the
gate fired and B finished before release; the final-state assertion fails
with two confirmed bookings instead of one. There are no worker exceptions.

Command (one invocation, exit status 1):

```bash
mvn -B -Dstyle.color=never -Dtest=RaceTest test
```

Full combined standard output and standard error:

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
2026-11-06T01:10:26.485Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=900 end=960
2026-11-06T01:10:26.487Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
race aim=hasOverlap call=2 paused=true fired=true targetCalls=4 customerBFinishedBeforeRelease=true
[ERROR] Tests run: 1, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 0.053 s <<< FAILURE! -- in edu.cmu.cs214.lab10.harness.RaceTest
[ERROR] edu.cmu.cs214.lab10.harness.RaceTest.overlappingRequestsConfirmAtMostOneBooking -- Time elapsed: 0.036 s <<< FAILURE!
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
[INFO] Total time:  1.655 s
[INFO] Finished at: 2026-11-05T20:10:26-05:00
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

**Your verdict.** Accept or reject, and what your milestone-1 result does and
does not show about the patch.

Reject the patch. The original test passes because the added second check
detects B's insertion and rejects A, proving only that this interleaving is
handled. Pausing after the second check instead lets both threads insert,
producing two overlapping confirmed bookings (`expected: <1> but was: <2>`).
The final check and insert are still separate operations, so the race window
remains and the ticket's invariant is still violated.

---

## Milestone 3: the fix you would ship

**Which option, and what you changed.** Name the lock or the atomic operation
and the two operations it now covers. Paste the diff of your change and the
passing run of your milestone-1 test and the full suite.

Chose the store-level atomic operation `tryInsertConfirmed(Booking)`.
`InMemoryBookingStore.tryInsertConfirmed()` holds the real store's `this`
monitor continuously across `hasOverlap()` and `bookings.add()`. The nested
synchronized overlap call is reentrant and does not release the outer lock.
A conflicting request returns `false` without modifying the store. The same
monitor protects `cancelAndPromote()`, so cancellation and promotion cannot
interleave between the check and insertion either.

`BookingService.book()` now uses this operation instead of the two separate
checks and unconditional insert. Confirmation statistics and logs are emitted
only on success; conflicts still return the existing rejection reason.
`PausingStore` delegates the new operation, and `RaceTest` restores the
milestone-1 aim, `hasOverlap, 1`. Thread-safety comments now describe the
actual atomic boundary.

Diff against the committed milestone-2 code (including the proposed patch):

```diff
diff --git a/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java b/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
index 5e6b639..ff82803 100644
--- a/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/harness/PausingStore.java
@@ -53,8 +53,8 @@ public final class PausingStore implements BookingStore {
     /**
      * @param delegate     the real store, which does all the work
      * @param targetMethod the {@link BookingStore} method to park inside, by
-     *     name: {@code hasOverlap}, {@code insert}, {@code bookingsForRoom}, or
-     *     {@code cancelAndPromote}
+     *     name: {@code hasOverlap}, {@code tryInsertConfirmed}, {@code insert},
+     *     {@code bookingsForRoom}, or {@code cancelAndPromote}
      * @param targetCall   which invocation of that method to park, counting
      *     from 1 across all threads
      */
@@ -101,6 +101,11 @@ public final class PausingStore implements BookingStore {
         return gate("hasOverlap", () -> delegate.hasOverlap(roomId, startMinute, endMinute));
     }
 
+    @Override
+    public boolean tryInsertConfirmed(Booking booking) {
+        return gate("tryInsertConfirmed", () -> delegate.tryInsertConfirmed(booking));
+    }
+
     @Override
     public void insert(Booking booking) {
         gate("insert", () -> {
diff --git a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
index 2482c85..56099ce 100644
--- a/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
+++ b/src/main/java/edu/cmu/cs214/lab10/service/BookingService.java
@@ -19,13 +19,14 @@ import java.util.concurrent.atomic.AtomicLong;
  * <p>Times are raw minutes from midnight and ranges are half-open, matching
  * {@link Booking}.
  *
- * <p>Thread safety: safe for concurrent use. The service owns no mutable
- * booking state of its own. The room catalog is built once in the constructor
- * and never written again, and every read and write of booking data goes
- * through {@link BookingStore}, whose methods are each synchronized. Since the
- * store is thread safe, the operations built on top of it are too. The only
- * other field that changes after construction is {@link BookingStats}, and its
- * numbers are documented as approximate.
+ * <p>Thread safety: safe for concurrent use. The room catalog is built once
+ * in the constructor and never written again. Booking uses the store's atomic
+ * {@link BookingStore#tryInsertConfirmed} operation, and cancellation and
+ * promotion use its atomic {@link BookingStore#cancelAndPromote} operation.
+ * These operations coordinate through the store even across service instances
+ * sharing the same data. The ID sequence is atomic within each service;
+ * callers sharing a store must still ensure IDs are unique across instances.
+ * {@link BookingStats} numbers are documented as approximate.
  */
 public final class BookingService {
 
@@ -67,18 +68,10 @@ public final class BookingService {
         if (startMinute < 0 || endMinute <= startMinute) {
             return reject(roomId, startMinute, endMinute, "empty or reversed slot");
         }
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
-            return reject(roomId, startMinute, endMinute, "slot already held");
-        }
         Booking booking = new Booking(nextId(), roomId, startMinute, endMinute, BookingStatus.CONFIRMED);
-        // Defensive re-check. Another request can take the slot between the
-        // check above and the insert below, so read the store once more right
-        // before writing. With the freshest possible answer in hand there is no
-        // window left for a second booking to slip through.
-        if (store.hasOverlap(roomId, startMinute, endMinute)) {
+        if (!store.tryInsertConfirmed(booking)) {
             return reject(roomId, startMinute, endMinute, "slot already held");
         }
-        store.insert(booking);
         stats.recordConfirmed();
         BookingLog.confirmed(booking);
         return BookingResult.confirmed(booking);
diff --git a/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java b/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
index 3ae3544..a631d2f 100644
--- a/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/store/BookingStore.java
@@ -25,7 +25,22 @@ public interface BookingStore {
      */
     boolean hasOverlap(String roomId, int startMinute, int endMinute);
 
-    /** Stores the booking. Ids are assumed unique; the store does not check. */
+    /**
+     * Inserts a CONFIRMED booking only if its room and range are free, checking
+     * and inserting as one atomic operation with respect to all store methods.
+     * Ids are assumed unique; the store does not check.
+     *
+     * @return true if inserted, or false if a confirmed booking overlaps;
+     *     a false result leaves the store unchanged
+     * @throws IllegalArgumentException if the booking is not CONFIRMED
+     */
+    boolean tryInsertConfirmed(Booking booking);
+
+    /**
+     * Stores the booking without checking for conflicts. Use
+     * {@link #tryInsertConfirmed} when requesting a confirmed slot.
+     * Ids are assumed unique; the store does not check.
+     */
     void insert(Booking booking);
 
     /**
diff --git a/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java b/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
index f7fd85f..fb5bdce 100644
--- a/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
+++ b/src/main/java/edu/cmu/cs214/lab10/store/InMemoryBookingStore.java
@@ -31,6 +31,20 @@ public final class InMemoryBookingStore implements BookingStore {
         return false;
     }
 
+    @Override
+    public synchronized boolean tryInsertConfirmed(Booking booking) {
+        if (booking.status() != BookingStatus.CONFIRMED) {
+            throw new IllegalArgumentException("booking must be CONFIRMED");
+        }
+        // Hold this store's monitor from the overlap check through insertion.
+        // The nested synchronized call is reentrant and does not release it.
+        if (hasOverlap(booking.roomId(), booking.startMinute(), booking.endMinute())) {
+            return false;
+        }
+        bookings.add(booking);
+        return true;
+    }
+
     @Override
     public synchronized void insert(Booking booking) {
         bookings.add(booking);
diff --git a/src/test/java/edu/cmu/cs214/lab10/harness/RaceTest.java b/src/test/java/edu/cmu/cs214/lab10/harness/RaceTest.java
index cf99407..d4ce266 100644
--- a/src/test/java/edu/cmu/cs214/lab10/harness/RaceTest.java
+++ b/src/test/java/edu/cmu/cs214/lab10/harness/RaceTest.java
@@ -23,7 +23,7 @@ class RaceTest {
         int startMinute = 900;
         int endMinute = 960;
         InMemoryBookingStore real = new InMemoryBookingStore();
-        PausingStore gated = new PausingStore(real, "hasOverlap", 2);
+        PausingStore gated = new PausingStore(real, "hasOverlap", 1);
         BookingService service = new BookingService(gated,
                 List.of(new Room(roomId, "Wean 5409", 12)));
         AtomicReference<Throwable> workerFailure = new AtomicReference<>();
@@ -54,7 +54,7 @@ class RaceTest {
 
         // Report the choreography without making it the regression assertion:
         // a fix may change or bypass this store call while preserving the invariant.
-        System.out.println("race aim=hasOverlap call=2 paused=" + paused
+        System.out.println("race aim=hasOverlap call=1 paused=" + paused
                 + " fired=" + gated.fired() + " targetCalls=" + gated.targetCallCount()
                 + " customerBFinishedBeforeRelease=" + secondFinishedBeforeRelease);
         assertFalse(first.isAlive(), "customer A must finish before inspecting the store");
@@ -96,10 +96,10 @@ class RaceTest {
     //   new PausingStore(real, "bookingsForRoom", 1)   // park call 1
     //   new PausingStore(real, "insert", 3)            // park call 3 of insert
     //
-    // Valid method names are the four on BookingStore: hasOverlap, insert,
-    // bookingsForRoom, cancelAndPromote. On the chosen call the wrapper
-    // delegates, then parks the calling thread before returning the answer it
-    // already computed. The gate fires once and once only.
+    // Valid method names are the five on BookingStore: hasOverlap,
+    // tryInsertConfirmed, insert, bookingsForRoom, cancelAndPromote. On the
+    // chosen call the wrapper delegates, then parks the calling thread before
+    // returning the answer it already computed. The gate fires once and once only.
     // ------------------------------------------------------------------
 
     // ------------------------------------------------------------------
```

The following are the complete captured outputs from the previous code-change
step, not new runs performed while writing this document. Both commands
exited with status 0.

### Original milestone-1 test: passing

```bash
mvn -B -Dstyle.color=never -Dtest=RaceTest test
```

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
[INFO] Recompiling the module because of changed source code.
[INFO] Compiling 11 source files with javac [debug release 21] to target/classes
[INFO] 
[INFO] --- resources:3.4.0:testResources (default-testResources) @ lab10-reservations ---
[INFO] skip non existing resourceDirectory /Users/tongfenghua/My mac/CMU-Learning/Agent Eng/f26-lab10/src/test/resources
[INFO] 
[INFO] --- compiler:3.13.0:testCompile (default-testCompile) @ lab10-reservations ---
[INFO] Recompiling the module because of changed dependency.
[INFO] Compiling 3 source files with javac [debug release 21] to target/test-classes
[INFO] 
[INFO] --- surefire:3.2.5:test (default-test) @ lab10-reservations ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO] 
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running edu.cmu.cs214.lab10.harness.RaceTest
2026-11-06T02:04:40.492Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T02:04:42.498Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
race aim=hasOverlap call=1 paused=false fired=false targetCalls=0 customerBFinishedBeforeRelease=true
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.056 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  4.022 s
[INFO] Finished at: 2026-11-05T21:04:42-05:00
[INFO] ------------------------------------------------------------------------
```

### Full suite: passing (12 tests)

```bash
mvn -B -Dstyle.color=never test
```

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
2026-11-06T02:04:53.850Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=900 end=960
2026-11-06T02:04:55.856Z INFO booking.rejected room=WEH-5409 start=900 end=960 reason=slot already held
race aim=hasOverlap call=1 paused=false fired=false targetCalls=0 customerBFinishedBeforeRelease=true
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.050 s -- in edu.cmu.cs214.lab10.harness.RaceTest
[INFO] Running edu.cmu.cs214.lab10.service.BookingServiceTest
2026-11-06T02:04:55.883Z INFO booking.rejected room=PH-100 start=540 end=600 reason=unknown room
2026-11-06T02:04:55.884Z INFO booking.rejected room=WEH-5409 start=600 end=540 reason=empty or reversed slot
2026-11-06T02:04:55.884Z INFO booking.rejected room=WEH-5409 start=600 end=600 reason=empty or reversed slot
2026-11-06T02:04:55.885Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T02:04:55.886Z INFO booking.rejected room=WEH-5409 start=570 end=630 reason=slot already held
2026-11-06T02:04:55.887Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T02:04:55.887Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=600 end=660
2026-11-06T02:04:55.889Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T02:04:55.889Z INFO booking.confirmed booking=bk-2 room=GHC-4301 start=540 end=600
2026-11-06T02:04:55.890Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T02:04:55.891Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T02:04:55.891Z INFO booking.confirmed booking=bk-2 room=WEH-5409 start=600 end=660
2026-11-06T02:04:55.893Z INFO booking.confirmed booking=bk-1 room=WEH-5409 start=540 end=600
2026-11-06T02:04:55.893Z INFO booking.waitlisted booking=bk-2 room=WEH-5409 start=540 end=600
2026-11-06T02:04:55.893Z INFO booking.cancelled booking=bk-1
2026-11-06T02:04:55.893Z INFO booking.promoted booking=bk-2 room=WEH-5409 start=540 end=600
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.015 s -- in edu.cmu.cs214.lab10.service.BookingServiceTest
[INFO] Running edu.cmu.cs214.lab10.domain.BookingTest
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.006 s -- in edu.cmu.cs214.lab10.domain.BookingTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 12, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  3.605 s
[INFO] Finished at: 2026-11-05T21:04:55-05:00
[INFO] ------------------------------------------------------------------------
```

**Is your milestone-1 test still testing anything?** After your fix, describe
what that test now exercises. Did `PausingStore` pause a call during the green run? How do
you know?

The gate did not fire: both runs print
`paused=false fired=false targetCalls=0`. The service now calls the wrapper's
`tryInsertConfirmed()`. Its delegate calls `hasOverlap()` on the real store
internally, so that check never passes through the wrapper's `hasOverlap()`
gate. `awaitPaused()` times out after 2 seconds; in these runs A completed
before B started, as the confirmation and rejection timestamps also show.

The test still checks that both workers finish without exceptions and that
the real store contains exactly one confirmed booking overlapping the
requested slot after two requests. The logs additionally show one
confirmation and one rejection. It now exercises effectively sequential
requests through the new API, rather than forcing the original race. Its
green result alone does not demonstrate correctness under contention or
all possible interleavings; the reason the original window is closed is
the single store lock held across the check and insertion.

**What runs one at a time now that ran in parallel before.** Name concretely which callers
queue behind which. "Nothing, it is just correct now" does not count as an
answer.

Before, individual store calls already ran one at a time, but complete booking
attempts could interleave their checks and inserts. Now B's
`tryInsertConfirmed()` must wait while A holds the real store monitor for
its entire check and insertion. This applies even to different rooms and
to different service instances or wrappers sharing that same real store.

`cancelBooking()` via `cancelAndPromote()`, `joinWaitlist()` via `insert()`,
`listBookings()` via `bookingsForRoom()`, and direct `hasOverlap()` or `size()`
calls also wait for that monitor. They already waited during individual
store calls; now none can run in the interval between a booking's check and
insertion. Input validation, ID generation, statistics, and logging remain
outside this critical section. Operations on independent stores do not
share this lock.

**What you left unfixed.** For anything your review flagged that you chose not
to fix, one line each on why.

- `BookingStats`: lost increments and stale reads remain intentionally approximate; these counters do not drive booking decisions.
- `sequence` / `nextId()`: IDs remain unique only within one service instance; allocation across services sharing a store is a separate issue from the ticket's distinct-ID overlapping bookings.
- Raw `BookingStore.insert()`: retains its existing unchecked contract for other uses; an external caller inserting a conflicting confirmed booking directly can bypass the invariant, so confirmed reservation requests must use `tryInsertConfirmed()` as the service now does.

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

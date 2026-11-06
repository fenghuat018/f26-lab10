package edu.cmu.cs214.lab10.harness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import edu.cmu.cs214.lab10.domain.BookingStatus;
import edu.cmu.cs214.lab10.domain.Room;
import edu.cmu.cs214.lab10.service.BookingService;
import edu.cmu.cs214.lab10.store.InMemoryBookingStore;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Concurrency regression tests for the confirmed-booking invariant.
 */
class RaceTest {

    @Test
    void overlappingRequestsConfirmAtMostOneBooking() throws InterruptedException {
        String roomId = "WEH-5409";
        int startMinute = 900;
        int endMinute = 960;
        InMemoryBookingStore real = new InMemoryBookingStore();
        PausingStore gated = new PausingStore(real, "hasOverlap", 1);
        BookingService service = new BookingService(gated,
                List.of(new Room(roomId, "Wean 5409", 12)));
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();

        Runnable book = () -> service.book(roomId, startMinute, endMinute);
        Thread first = new Thread(book, "customer-A");
        Thread second = new Thread(book, "customer-B");
        Thread.UncaughtExceptionHandler recordFailure =
                (thread, failure) -> workerFailure.compareAndSet(null, failure);
        first.setUncaughtExceptionHandler(recordFailure);
        second.setUncaughtExceptionHandler(recordFailure);

        boolean paused;
        boolean secondFinishedBeforeRelease;
        first.start();
        try {
            // A has computed its overlap answer, but has not received it yet.
            paused = gated.awaitPaused();
            second.start();
            // Let B finish its check and insert before A receives its old answer.
            second.join(PausingStore.TIMEOUT_MILLIS);
            secondFinishedBeforeRelease = !second.isAlive();
        } finally {
            gated.release();
            first.join(PausingStore.TIMEOUT_MILLIS);
            second.join(PausingStore.TIMEOUT_MILLIS);
        }

        // Report the choreography without making it the regression assertion:
        // a fix may change or bypass this store call while preserving the invariant.
        System.out.println("race aim=hasOverlap call=1 paused=" + paused
                + " fired=" + gated.fired() + " targetCalls=" + gated.targetCallCount()
                + " customerBFinishedBeforeRelease=" + secondFinishedBeforeRelease);
        assertFalse(first.isAlive(), "customer A must finish before inspecting the store");
        assertFalse(second.isAlive(), "customer B must finish before inspecting the store");
        assertNull(workerFailure.get(), "booking threads must not fail with an exception");

        // Read the real store so verification cannot trigger or count wrapper calls.
        long confirmed = real.bookingsForRoom(roomId).stream()
                .filter(booking -> booking.status() == BookingStatus.CONFIRMED)
                .filter(booking -> booking.overlaps(startMinute, endMinute))
                .count();
        assertEquals(1L, confirmed,
                "A room must have exactly one confirmed booking after two requests for the same free slot");
    }

    // ------------------------------------------------------------------
    // Wiring example 1: wrap the store
    //
    // PausingStore is a BookingStore that wraps another BookingStore. Build the
    // real store, wrap it, and hand the wrapper to the service. The service
    // cannot tell the difference.
    //
    //   InMemoryBookingStore real = new InMemoryBookingStore();
    //   PausingStore gated = new PausingStore(real, "bookingsForRoom", 1);
    //   BookingService service = new BookingService(gated, ROOMS);
    //
    // (ROOMS is the room list, as in BookingServiceTest.)
    //
    // Assert against `real`, not `gated`: you want the end state of the store,
    // and reading through the wrapper counts as a call.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 2: aim the gate
    //
    // The two constructor arguments after the delegate are a method name and a
    // call index counting from 1 across all threads:
    //
    //   new PausingStore(real, "bookingsForRoom", 1)   // park call 1
    //   new PausingStore(real, "insert", 3)            // park call 3 of insert
    //
    // Valid method names are the five on BookingStore: hasOverlap,
    // tryInsertConfirmed, insert, bookingsForRoom, cancelAndPromote. On the
    // chosen call the wrapper delegates, then parks the calling thread before
    // returning the answer it already computed. The gate fires once and once only.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // Wiring example 3: the two-thread pattern
    //
    // Every wait below is bounded, so this pattern cannot hang no matter what
    // the code under test does. Do not add a sleep to it; a sleep is a guess,
    // and the latches are not. (Your test method needs
    // `throws InterruptedException` for the joins.)
    //
    // The scenario here is deliberately harmless: it parks a reader in
    // bookingsForRoom (the aim from example 1) while a writer books a
    // different room. It exists to show the choreography, not a bug.
    //
    //   Thread reader = new Thread(() -> service.listBookings("WEH-5409"));
    //   reader.start();
    //
    //   gated.awaitPaused();          // returns when a thread is parked, or
    //                                 // false after PausingStore.TIMEOUT_MILLIS
    //                                 // if the gated call never happened
    //
    //   Thread writer = new Thread(() -> service.book("GHC-4301", 720, 780));
    //   writer.start();
    //   writer.join(PausingStore.TIMEOUT_MILLIS);
    //
    //   gated.release();              // the parked reader returns and finishes
    //   reader.join(PausingStore.TIMEOUT_MILLIS);
    //                                 // note the writer needed no release: it
    //                                 // was never blocked by the parked reader
    //
    // Then assert on the end state, never on the order things happened in. For
    // the harmless scenario above, the writer's booking is stored whatever the
    // reader saw:
    //
    //   assertEquals(1, real.bookingsForRoom("GHC-4301").size());
    //
    // An end-state assertion still means something after you change the code
    // underneath it. A schedule assertion does not.
    // ------------------------------------------------------------------
}

import ru.romariogi.kanjihour.RefreshCancellation;
import ru.romariogi.kanjihour.RefreshTask;

import java.util.ArrayDeque;
import java.util.Queue;

/** Regression tests for the dispatcher, not Android/e2e tests.
 * The real RefreshTask runs against a controllable clock, a delayed executor,
 * and callbacks modelling hourly selection and same-PendingIntent replacement.
 * No sleeps, system-clock edits, Activity or wallpaper-change broadcast are used.
 */
public final class RefreshTaskHourBoundaryTest {
    private static final long HOUR = 3_600_000L;
    private static int checks, scenarios, failures;

    private static final class Fixture {
        final Queue<Runnable> queue = new ArrayDeque<>();
        long now, alarmAt, shownHour = -1;
        int arms, loads, writes, errors, completions;

        Fixture(long now) { this.now = now; }

        void arm() {
            // AlarmManager replaces the pending alarm for the same PendingIntent.
            alarmAt = (Math.floorDiv(now, HOUR) + 1) * HOUR;
            arms++;
        }

        void dispatch(long finishedAt, RefreshCancellation cancellation, Runnable beforeWrite) {
            RefreshTask.submit(queue::add, this::arm, () -> {
                loads++;
                long selectedHour = Math.floorDiv(now, HOUR);
                now = finishedAt; // Rendering/storage spans the boundary, without a real wait.
                beforeWrite.run();
                cancellation.throwIfCancelled();
                shownHour = selectedHour;
                writes++;
            }, error -> errors++, () -> completions++, cancellation);
        }

        void dispatch(long finishedAt) {
            dispatch(finishedAt, new RefreshCancellation(), () -> {});
        }

        void drain() {
            int count = 0;
            while (!queue.isEmpty()) {
                require(++count <= 10, "unexpected refresh loop");
                queue.remove().run();
            }
        }

        void deliverDueAlarm() {
            require(alarmAt != 0 && alarmAt <= now, "no due alarm left to deliver");
            alarmAt = 0; // System consumes the one-shot event before dispatching it.
            dispatch(now);
            drain();
        }
    }

    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    private static void equal(long actual, long expected, String message) {
        require(actual == expected, message + ": expected " + expected + ", got " + actual);
    }

    private static void scenario(String name, Runnable test) {
        scenarios++;
        try {
            test.run();
            System.out.println("PASS " + name);
        } catch (AssertionError failure) {
            failures++;
            System.err.println("FAIL " + name + ": " + failure.getMessage());
        }
    }

    private static void crossing(long start, long end) {
        Fixture f = new Fixture(start);
        long firstDeadline = (Math.floorDiv(start, HOUR) + 1) * HOUR;
        f.dispatch(end);
        equal(f.alarmAt, firstDeadline, "next alarm must exist before queued work");
        f.drain();
        equal(f.shownHour, Math.floorDiv(start, HOUR), "first write uses its selected hour");
        equal(f.alarmAt, firstDeadline,
                "undelivered hourly alarm must not be postponed after a slow write");
        equal(f.arms, 1, "completion must not arm again");
        equal(f.completions, 1, "one completion");
        equal(f.errors, 0, "successful work");
        f.deliverDueAlarm();
        equal(f.shownHour, Math.floorDiv(end, HOUR), "delivered alarm catches up to current hour");
        equal(f.alarmAt, (Math.floorDiv(end, HOUR) + 1) * HOUR, "next future hour after catch-up");
        equal(f.writes, 2, "no replay of all missed hours");
    }

    public static void main(String[] args) {
        scenario("11:59:59 -> 12:00:01, no self wallpaper signal", () ->
                crossing(12 * HOUR - 1000, 12 * HOUR + 1000));
        scenario("midnight", () -> crossing(24 * HOUR - 1000, 24 * HOUR + 1000));
        scenario("several missed hours", () -> crossing(12 * HOUR - 1000, 15 * HOUR + 1000));
        scenario("no boundary crossed", () -> {
            Fixture f = new Fixture(12 * HOUR - 1000);
            f.dispatch(12 * HOUR - 500);
            f.drain();
            equal(f.shownHour, 11, "same hour");
            equal(f.alarmAt, 12 * HOUR, "future alarm retained");
            equal(f.completions, 1, "completion retained");
        });
        scenario("hour crossed while waiting in queue", () -> {
            Fixture f = new Fixture(12 * HOUR - 1000);
            f.dispatch(12 * HOUR + 1000);
            f.now = 12 * HOUR + 500;
            f.drain();
            equal(f.shownHour, 12, "selection happens at execution, not dispatch");
            equal(f.alarmAt, 12 * HOUR, "queued task does not discard due event");
        });
        scenario("failed render across boundary", () -> {
            Fixture f = new Fixture(12 * HOUR - 1000);
            f.dispatch(12 * HOUR + 1000, new RefreshCancellation(), () -> {
                throw new IllegalStateException("render failed");
            });
            f.drain();
            equal(f.errors, 1, "failure reported");
            equal(f.completions, 1, "receiver completion retained on failure");
            equal(f.writes, 0, "no partial successful write");
            equal(f.alarmAt, 12 * HOUR, "failure must preserve due alarm");
            f.deliverDueAlarm();
            equal(f.shownHour, 12, "later event recovers from failure");
        });
        scenario("recovery cancelled while queued across boundary", () -> {
            Fixture f = new Fixture(12 * HOUR - 1000);
            RefreshCancellation cancellation = new RefreshCancellation();
            f.dispatch(12 * HOUR + 1000, cancellation, () -> {});
            f.now = 12 * HOUR + 1000;
            cancellation.cancel();
            f.drain();
            equal(f.loads, 0, "cancelled job does not load");
            equal(f.writes, 0, "cancelled job does not write");
            equal(f.errors, 0, "cancellation is not failure");
            equal(f.completions, 1, "one cancellation completion");
            equal(f.alarmAt, 12 * HOUR, "cancelled recovery leaves independent alarm due");
            f.deliverDueAlarm();
            equal(f.shownHour, 12, "independent alarm still works");
        });
        scenario("recovery cancelled during render across boundary", () -> {
            Fixture f = new Fixture(12 * HOUR - 1000);
            RefreshCancellation cancellation = new RefreshCancellation();
            f.dispatch(12 * HOUR + 1000, cancellation, cancellation::cancel);
            f.drain();
            equal(f.writes, 0, "no new write after cancellation");
            equal(f.errors, 0, "cancellation is not failure");
            equal(f.completions, 1, "one completion");
            equal(f.alarmAt, 12 * HOUR, "cancellation does not replace independent alarm");
        });
        scenario("optional wallpaper signal still catches up", () -> {
            Fixture f = new Fixture(12 * HOUR - 1000);
            f.dispatch(12 * HOUR + 1000, new RefreshCancellation(), () -> f.dispatch(f.now));
            f.drain();
            equal(f.shownHour, 12, "additional refresh selects current hour");
            equal(f.alarmAt, 13 * HOUR, "new refresh owns next alarm");
            equal(f.writes, 2, "no refresh loop");
            equal(f.completions, 2, "each dispatch completes once");
        });
        System.out.println("RefreshTaskHourBoundaryTest: " + scenarios + " scenarios, "
                + checks + " checks, " + failures + " failures");
        if (failures != 0) throw new AssertionError(failures + " hour-boundary scenarios failed");
    }
}

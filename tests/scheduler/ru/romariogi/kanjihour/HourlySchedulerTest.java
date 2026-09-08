package ru.romariogi.kanjihour;
import android.content.Context;
import java.io.File;

/** Real HourlyScheduler: no replacement of its algorithm with a counting stub. */
public final class HourlySchedulerTest {
    private static int checks;
    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
    private static Context fresh(boolean lock, int widgets) {
        Context context = new Context(new File("unused-scheduler-test"));
        context.prefs.edit().putBoolean("lock_enabled", lock).commit();
        KanjiWidgetProvider.count = widgets;
        KanjiWidgetProvider.unavailable = false;
        return context;
    }
    public static void main(String[] args) {
        Context context = fresh(true, 0);
        // Reliably in the past, including when this test itself crosses :00.
        long due = Math.floorDiv(System.currentTimeMillis(), HourlyScheduler.HOUR_MILLIS)
                * HourlyScheduler.HOUR_MILLIS - HourlyScheduler.HOUR_MILLIS;
        context.prefs.edit().putLong("next_update_at", due).commit();
        context.alarms.next = due;
        HourlyScheduler.schedule(context);
        require(context.alarms.next == due,
                "another trigger postponed an undelivered alarm: " + due + " -> " + context.alarms.next);
        require(context.jobs.pending != null, "lock-only recovery exists");
        HourlyScheduler.schedule(context);
        require(context.alarms.next == due, "repeated scheduling preserves the due event");
        require(context.jobs.schedules == 1, "periodic recovery was not reset");

        // Reissuing hints repairs deleted alarms and permission changes, not just memory.
        context.alarms.next = 0;
        context.alarms.exact = true;
        HourlyScheduler.schedule(context);
        require(context.alarms.next == due && context.alarms.installedExact, "lost exact event re-armed");
        context.alarms.revokedDuringSet = true;
        HourlyScheduler.schedule(context);
        require(context.alarms.next == due && !context.alarms.installedExact,
                "revocation during set falls back without discarding due event");
        long before = System.currentTimeMillis();
        HourlyScheduler.schedule(context, true);
        long after = System.currentTimeMillis();
        require(context.alarms.next >= HourlyScheduler.nextHour(before)
                && context.alarms.next <= HourlyScheduler.nextHour(after),
                "delivery advances the event to the next hour");
        require(context.prefs.getLong("next_update_at", 0) == context.alarms.next,
                "diagnostics reflect the registered event");

        context = fresh(false, 1);
        context.jobs.rejected = true;
        HourlyScheduler.schedule(context);
        require(context.alarms.next > 0, "recovery failure does not block widget-only alarm");
        require(!context.prefs.getString("recovery_error", "").isEmpty(), "recovery rejection recorded");
        context.jobs.rejected = false;
        context.prefs.edit().putLong("next_update_at", System.currentTimeMillis() + 7200000L).commit();
        before = System.currentTimeMillis();
        HourlyScheduler.schedule(context);
        after = System.currentTimeMillis();
        require(context.alarms.next >= HourlyScheduler.nextHour(before)
                && context.alarms.next <= HourlyScheduler.nextHour(after),
                "clock moved backwards: distant hint must not delay the next hour");
        require(context.prefs.getString("recovery_error", "").isEmpty(), "recovery can retry");

        context = fresh(false, 0);
        KanjiWidgetProvider.unavailable = true;
        HourlyScheduler.schedule(context);
        require(context.alarms.next > 0 && context.jobs.pending != null,
                "unknown launcher state must not break independent recovery");
        KanjiWidgetProvider.unavailable = false;
        HourlyScheduler.schedule(context);
        require(context.alarms.next == 0 && context.jobs.pending == null,
                "confirmed disabled surfaces cancel both schedules");
        require(context.prefs.getLong("next_update_at", 0) == 0, "off removes the old hint");
        context.prefs.edit().putBoolean("lock_enabled", true).commit();
        HourlyScheduler.schedule(context);
        require(context.alarms.next > 0 && context.jobs.pending != null, "reenable schedules again");
        System.out.println("HourlySchedulerTest: " + checks + " checks passed");
    }
}

package ru.romariogi.kanjihour;

import android.content.Context;
import java.io.File;

/** A failed AlarmManager call is not evidence that its earlier event was delivered. */
public final class AlarmRegistrationFailureTest {
    private static int checks, failures;
    private static void require(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    private static Context fresh() {
        Context context = new Context(new File("unused-registration-test"));
        context.prefs.edit().putBoolean("lock_enabled", true).commit();
        return context;
    }
    private static void retryPending(boolean exact, boolean delivered) {
        Context context = fresh();
        long due = (Math.floorDiv(System.currentTimeMillis(), HourlyScheduler.HOUR_MILLIS) - 1)
                * HourlyScheduler.HOUR_MILLIS;
        context.prefs.edit().putLong("next_update_at", due).commit();
        context.alarms.next = delivered ? 0 : due;
        context.alarms.exact = exact;
        context.alarms.registrationFails = true;
        HourlyScheduler.schedule(context, delivered);
        require("error".equals(context.prefs.getString("schedule_mode", "")), "failed registration must be reported");
        require(context.prefs.getLong("next_update_at", 0) == due,
                "failed registration erased the deadline; retry would postpone it to next hour");
        require(context.alarms.next == (delivered ? 0 : due), "fake failure unexpectedly installed an alarm");
        context.alarms.registrationFails = false;
        HourlyScheduler.schedule(context);
        require(context.alarms.next == due, "retry must restore the original deadline, not next hour");
        require(context.prefs.getLong("next_update_at", 0) == due, "retry deadline metadata differs");
        require(context.prefs.getString("schedule_error", "").isEmpty(), "successful retry must clear its error");
        require((exact ? "exact" : "inexact").equals(context.prefs.getString("schedule_mode", "")),
                "retry mode must match permission");
        HourlyScheduler.schedule(context, true);
        require(context.alarms.next > due, "successful delivery must advance, not repeatedly re-arm the same deadline");
    }
    private static void scenario(String name, Runnable test) {
        try { test.run(); System.out.println("PASS " + name); }
        catch (AssertionError error) { failures++; System.err.println("FAIL " + name + ": " + error.getMessage()); }
    }
    public static void main(String[] args) {
        scenario("failed inexact registration", () -> retryPending(false, false));
        scenario("failed exact registration", () -> retryPending(true, false));
        scenario("failed registration after delivery", () -> retryPending(false, true));
        scenario("first registration has no older deadline", () -> {
            Context context = fresh(); context.alarms.registrationFails = true;
            HourlyScheduler.schedule(context);
            require(context.prefs.getLong("next_update_at", 0) == 0, "failure must not invent a confirmed deadline");
            context.alarms.registrationFails = false;
            long before = System.currentTimeMillis();
            HourlyScheduler.schedule(context);
            require(context.alarms.next >= HourlyScheduler.nextHour(before)
                    && context.alarms.next <= HourlyScheduler.nextHour(System.currentTimeMillis()), "first retry needs next hour");
            context.prefs.edit().putBoolean("lock_enabled", false).commit();
            HourlyScheduler.schedule(context);
            require(context.prefs.getLong("next_update_at", 0) == 0 && context.alarms.next == 0,
                    "explicit disable must still clear the deadline and cancel the alarm");
        });
        System.out.println("AlarmRegistrationFailureTest: 4 scenarios, " + checks + " checks, " + failures + " failures");
        if (failures > 0) throw new AssertionError(failures + " registration retry regressions");
    }
}

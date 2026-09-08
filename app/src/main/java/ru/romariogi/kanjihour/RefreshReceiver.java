package ru.romariogi.kanjihour;

import android.app.AlarmManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Restores hourly surfaces after alarms, reboot, clock edits and upgrades. */
public final class RefreshReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !handles(intent.getAction())) return;
        final PendingResult pending = goAsync();
        UpdateCoordinator.refresh(context, false, intent.getAction(), () -> {
            if (pending != null) pending.finish();
        });
    }
    private static boolean handles(String action) {
        return HourlyScheduler.ACTION_REFRESH.equals(action)
                || Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED.equals(action);
    }
}

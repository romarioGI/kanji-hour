package ru.romariogi.kanjihour;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

/** Short system-managed recovery, not a foreground or permanently running service. */
public final class RecoveryJobService extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private JobParameters active;

    @Override public boolean onStartJob(JobParameters params) {
        active = params;
        UpdateCoordinator.refresh(this, false, "recovery", () -> main.post(() -> {
            if (active == params) {
                active = null;
                jobFinished(params, false);
            }
        }));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        if (active == params) active = null;
        // The next alarm was armed before dispatch; the periodic job remains as backup.
        return true;
    }
}

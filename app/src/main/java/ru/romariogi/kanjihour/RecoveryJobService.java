package ru.romariogi.kanjihour;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.os.Handler;
import android.os.Looper;

/** Short system-managed recovery, not a foreground or permanently running service. */
public final class RecoveryJobService extends JobService {
    private final Handler main = new Handler(Looper.getMainLooper());
    private Run active;

    private static final class Run {
        final JobParameters params;
        final RefreshCancellation cancellation = new RefreshCancellation();
        Run(JobParameters params) { this.params = params; }
    }

    @Override public boolean onStartJob(JobParameters params) {
        if (active != null) active.cancellation.cancel();
        Run run = new Run(params);
        active = run;
        UpdateCoordinator.refresh(this, false, "recovery", run.cancellation, () -> main.post(() -> {
            // A late completion from a stopped run must not finish a replacement run.
            if (active == run && !run.cancellation.isCancelled()) {
                active = null;
                jobFinished(run.params, false);
            }
        }));
        return true;
    }

    @Override public boolean onStopJob(JobParameters params) {
        // JobParameters is parcelled by Android; do not depend on object identity.
        if (active != null && active.params.getJobId() == params.getJobId()) {
            active.cancellation.cancel();
            active = null;
        }
        // This requests a new run, not permission to continue the stopped one.
        return true;
    }

    @Override public void onDestroy() {
        if (active != null) {
            active.cancellation.cancel();
            active = null;
        }
        super.onDestroy();
    }
}

package android.app.job;
public final class JobScheduler {
    public static final int RESULT_SUCCESS = 1;
    public JobInfo pending;
    public int schedules;
    public boolean rejected;
    public JobInfo getPendingJob(int id) { return pending; }
    public int schedule(JobInfo job) {
        if (rejected) return 0;
        pending = job; schedules++; return RESULT_SUCCESS;
    }
    public void cancel(int id) { pending = null; }
}

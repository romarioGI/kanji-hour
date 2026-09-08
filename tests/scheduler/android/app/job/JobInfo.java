package android.app.job;
import android.content.ComponentName;
public final class JobInfo {
    final int id;
    private long interval;
    private boolean persisted;
    private JobInfo(int id) { this.id = id; }
    public long getIntervalMillis() { return interval; }
    public boolean isPersisted() { return persisted; }
    public static final class Builder {
        private final JobInfo job;
        public Builder(int id, ComponentName name) { job = new JobInfo(id); }
        public Builder setPeriodic(long value) { job.interval = value; return this; }
        public Builder setPersisted(boolean value) { job.persisted = value; return this; }
        public JobInfo build() { return job; }
    }
}

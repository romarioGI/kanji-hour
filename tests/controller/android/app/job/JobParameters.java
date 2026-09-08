package android.app.job;
public final class JobParameters {
    private final int id;
    public JobParameters(int id) { this.id = id; }
    public int getJobId() { return id; }
}

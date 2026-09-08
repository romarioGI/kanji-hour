package android.app.job;
import android.content.Context;
import java.io.File;
/** Test-only service context. No real Android job scheduling or wake locks. */
public abstract class JobService extends Context {
    public int finishes;
    public JobParameters lastFinished;
    public JobService() { super(new File(System.getProperty("test.files"))); }
    public abstract boolean onStartJob(JobParameters params);
    public abstract boolean onStopJob(JobParameters params);
    public void jobFinished(JobParameters params, boolean retry) { finishes++; lastFinished = params; }
    public void onDestroy() { }
}

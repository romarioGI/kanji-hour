package android.os;
import java.util.concurrent.ConcurrentLinkedQueue;
/** Test-only main callback queue; explicitly drained by tests, not a real Looper. */
public final class Handler {
    private static final ConcurrentLinkedQueue<Runnable> QUEUE = new ConcurrentLinkedQueue<>();
    public Handler(Looper looper) { }
    public boolean post(Runnable work) { QUEUE.add(work); return true; }
    public static void drain() {
        Runnable work;
        while ((work = QUEUE.poll()) != null) work.run();
    }
}

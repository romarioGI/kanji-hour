import ru.romariogi.kanjihour.PreviewRequests;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Production preview dispatcher, with deterministic worker and UI queues; no Android UI. */
public final class PreviewRequestsTest {
    private static int checks;
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static final class Tasks implements Executor {
        final Queue<Runnable> queue = new ArrayDeque<>();
        public void execute(Runnable work) { queue.add(work); }
        void drain() { while (!queue.isEmpty()) queue.remove().run(); }
    }
    private static final class Image { int releases; }
    private static final class Fixture {
        final Tasks worker = new Tasks(), ui = new Tasks();
        int loads, publications;
        Image shown;
        Exception error;
        final PreviewRequests<Image> preview = new PreviewRequests<>(worker, ui, image -> image.releases++,
                (image, problem) -> { shown = image; error = problem; publications++; });
        Image load() { loads++; return new Image(); }
        void drain() { worker.drain(); ui.drain(); }
    }
    public static void main(String[] args) {
        Fixture f = new Fixture();
        f.preview.request(f::load);
        f.drain();
        require(f.loads == 0 && f.publications == 0, "no work before start");
        f.preview.start();
        f.preview.request(f::load);
        f.preview.stop();
        f.drain();
        require(f.loads == 0, "stop invalidates queued load");
        require(!f.preview.isStarted(), "stopped is distinct from destroyed");
        f.preview.request(f::load);
        require(f.worker.queue.isEmpty(), "stopped request is not even queued");

        f.preview.start();
        f.preview.request(f::load);
        f.drain();
        require(f.loads == 1 && f.publications == 1, "return loads current preview once");
        require(f.shown.releases == 0, "displayed image belongs to UI");

        f = new Fixture();
        f.preview.start();
        f.preview.request(f::load);
        f.preview.request(f::load);
        f.drain();
        require(f.loads == 1 && f.publications == 1, "newer request supersedes queued request");

        f = new Fixture();
        f.preview.start();
        Image stale = new Image();
        f.preview.request(() -> stale);
        f.worker.drain();
        require(f.ui.queue.size() == 1, "result waiting on UI queue");
        f.preview.stop();
        f.preview.start();
        f.ui.drain();
        require(f.publications == 0, "old callback cannot publish into restarted screen");
        require(stale.releases == 1, "stale result released once");
        f.preview.request(f::load);
        f.drain();
        require(f.publications == 1 && f.shown != stale, "new visible request succeeds");

        f = new Fixture();
        f.preview.start();
        Fixture current = f;
        Image duringStop = new Image();
        f.preview.request(() -> { current.preview.stop(); return duringStop; });
        f.drain();
        require(duringStop.releases == 1, "in-flight result discarded after stop");
        require(f.publications == 0 && f.ui.queue.isEmpty(), "no UI work for invisible result");

        f = new Fixture();
        f.preview.start();
        f.preview.request(() -> { throw new IllegalStateException("decode failed"); });
        f.drain();
        require(f.error != null && "decode failed".equals(f.error.getMessage()), "visible error reported");
        require(f.publications == 1 && f.shown == null, "failure does not display a fallback image");

        Tasks ui = new Tasks();
        int[] errors = {0};
        Executor rejected = task -> { throw new RejectedExecutionException(); };
        PreviewRequests<Image> rejectedWorker = new PreviewRequests<>(rejected, ui, image -> image.releases++,
                (image, error) -> { if (error instanceof RejectedExecutionException) errors[0]++; });
        rejectedWorker.start();
        rejectedWorker.request(Image::new);
        ui.drain();
        require(errors[0] == 1, "queue rejection reported while visible");
        rejectedWorker.request(Image::new);
        rejectedWorker.stop();
        ui.drain();
        require(errors[0] == 1, "late queue rejection not shown after stop");

        Image undisplayed = new Image();
        PreviewRequests<Image> rejectedUi = new PreviewRequests<>(Runnable::run, rejected,
                image -> image.releases++, (image, error) -> { throw new AssertionError("UI must not run"); });
        rejectedUi.start();
        rejectedUi.request(() -> undisplayed);
        require(undisplayed.releases == 1, "image released when UI dispatch rejected");
        System.out.println("PreviewRequestsTest: " + checks + " checks passed");
    }
}

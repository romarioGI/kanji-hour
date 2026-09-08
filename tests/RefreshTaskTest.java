import ru.romariogi.kanjihour.RefreshTask;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

public final class RefreshTaskTest {
    private static int checks;
    private static void equal(Object actual, Object expected) {
        checks++;
        if (!actual.equals(expected)) throw new AssertionError(actual + " != " + expected);
    }
    public static void main(String[] args) {
        List<String> events = new ArrayList<>();
        List<Runnable> queue = new ArrayList<>();
        RefreshTask.submit(queue::add, () -> events.add("arm"), () -> events.add("work"),
                e -> events.add("error"), () -> events.add("finish"));
        equal(events, List.of("arm")); // Next alarm exists while worker is blocked.
        equal(queue.size(), 1);
        queue.remove(0).run();
        equal(events, List.of("arm", "work", "finish"));

        events.clear();
        RefreshTask.submit(Runnable::run, () -> events.add("arm"),
                () -> { events.add("work"); throw new IllegalStateException(); },
                e -> events.add("error"), () -> events.add("finish"));
        equal(events, List.of("arm", "work", "error", "finish"));

        events.clear();
        Executor rejected = task -> { throw new RejectedExecutionException(); };
        RefreshTask.submit(rejected, () -> events.add("arm"), () -> events.add("work"),
                e -> events.add("error"), () -> events.add("finish"));
        equal(events, List.of("arm", "error", "finish"));

        events.clear();
        RefreshTask.submit(queue::add, () -> { throw new IllegalStateException(); },
                () -> events.add("work"), e -> events.add("error"), () -> events.add("finish"));
        equal(events, List.of("error", "finish"));
        equal(queue.size(), 0);
        RefreshTask.submit(Runnable::run, () -> {}, () -> {}, e -> {}, null);
        checks++;
        System.out.println("RefreshTaskTest: " + checks + " checks passed");
    }
}

package ru.romariogi.kanjihour;

import java.util.concurrent.Executor;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Keeps the next wakeup independent of a slow, failed or rejected refresh. */
public final class RefreshTask {
    private RefreshTask() {}

    public static void submit(Executor executor, Runnable arm, Runnable work,
                              Consumer<RuntimeException> failure, Runnable completion) {
        submit(executor, arm, work, failure, completion, new RefreshCancellation());
    }

    public static void submit(Executor executor, Runnable arm, Runnable work,
                              Consumer<RuntimeException> failure, Runnable completion,
                              RefreshCancellation cancellation) {
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true) && completion != null) completion.run();
        };
        try {
            // Arm before entering the queue, not after wallpaper rendering.
            arm.run();
            executor.execute(() -> {
                try {
                    // A stopped job can still occupy a queue slot, but must do no work.
                    cancellation.throwIfCancelled();
                    work.run();
                } catch (CancellationException stopped) {
                    // Cancellation is not an update failure. Keep independent scheduling.
                } catch (RuntimeException error) {
                    failure.accept(error);
                } finally {
                    // Do not replace the still-pending hourly alarm after slow work.
                    // A refresh started before :00 can finish after :00 with the old
                    // glyph; re-arming here would silently postpone its catch-up.
                    // Enable/disable operations schedule their state changes explicitly.
                    finish.run();
                }
            });
        } catch (RuntimeException error) {
            try { failure.accept(error); } finally { finish.run(); }
        }
    }
}

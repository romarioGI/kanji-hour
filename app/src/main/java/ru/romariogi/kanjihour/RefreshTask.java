package ru.romariogi.kanjihour;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Keeps the next wakeup independent of a slow, failed or rejected refresh. */
public final class RefreshTask {
    private RefreshTask() {}

    public static void submit(Executor executor, Runnable arm, Runnable work,
                              Consumer<RuntimeException> failure, Runnable completion) {
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true) && completion != null) completion.run();
        };
        try {
            // Arm before entering the queue, not after wallpaper rendering.
            arm.run();
            executor.execute(() -> {
                try {
                    work.run();
                } catch (RuntimeException error) {
                    failure.accept(error);
                } finally {
                    try { arm.run(); } finally { finish.run(); }
                }
            });
        } catch (RuntimeException error) {
            try { failure.accept(error); } finally { finish.run(); }
        }
    }
}

package ru.romariogi.kanjihour;

import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** UI-thread lifecycle/request methods; worker results cross the visibility boundary safely. */
public final class PreviewRequests<T> {
    public interface Loader<T> { T load() throws Exception; }
    private final Executor worker, ui;
    private final Consumer<T> discard;
    private final BiConsumer<T, Exception> publish;
    private boolean started;
    private long generation;

    public PreviewRequests(Executor worker, Executor ui, Consumer<T> discard, BiConsumer<T, Exception> publish) {
        this.worker = worker; this.ui = ui; this.discard = discard; this.publish = publish;
    }
    public synchronized void start() { started = true; generation++; }
    public synchronized void stop() { started = false; generation++; }
    public synchronized boolean isStarted() { return started; }
    private synchronized boolean current(long version) { return started && version == generation; }

    public void request(Loader<T> loader) {
        final long version;
        synchronized (this) {
            if (!started) return;
            version = ++generation;
        }
        try {
            worker.execute(() -> {
                if (!current(version)) return;
                T result = null;
                Exception error = null;
                try { result = loader.load(); }
                catch (Exception failure) { error = failure; }
                deliver(version, result, error);
            });
        } catch (RejectedExecutionException rejected) {
            deliver(version, null, rejected);
        }
    }

    private void deliver(long version, T result, Exception error) {
        if (!current(version)) {
            release(result);
            return;
        }
        try {
            ui.execute(() -> {
                if (current(version)) publish.accept(result, error);
                else release(result);
            });
        } catch (RejectedExecutionException rejected) {
            release(result);
        }
    }
    private void release(T result) { if (result != null) discard.accept(result); }
}

package ru.romariogi.kanjihour;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Cancellation belongs to one refresh, never to the shared executor or hourly alarm. */
public final class RefreshCancellation {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public void cancel() { cancelled.set(true); }
    public boolean isCancelled() { return cancelled.get(); }
    public void throwIfCancelled() {
        if (isCancelled()) throw new CancellationException("Refresh stopped");
    }
}

package io.papermc.paper.storage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.jspecify.annotations.Nullable;

/** Ordered IO with independent failure barriers for each storage key. */
public final class OrderedIo<K> {
    private final Executor executor;
    private final Map<K, Pending> pending = new HashMap<>();
    private boolean closed;

    public OrderedIo(final Executor executor) {
        this.executor = executor;
    }

    public <T> CompletableFuture<T> submit(final List<K> keys, final boolean write, final Callable<T> action) {
        if (keys.isEmpty()) throw new IllegalArgumentException("At least one storage key is required");
        final List<K> ids = List.copyOf(keys);
        final List<Pending> states;
        final CompletableFuture<Void> previous;
        final CompletableFuture<Void> finished = new CompletableFuture<>();
        synchronized (this.pending) {
            if (this.closed) return CompletableFuture.failedFuture(new RejectedExecutionException("Storage is closed"));
            states = ids.stream().map(key -> this.pending.computeIfAbsent(key, ignored -> new Pending())).toList();
            previous = CompletableFuture.allOf(states.stream().map(state -> state.tail).toArray(CompletableFuture[]::new));
            states.forEach(state -> state.tail = finished);
        }

        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<T> operation;
        try {
            operation = previous.thenApplyAsync(ignored -> {
                if (!write) {
                    synchronized (this.pending) {
                        for (Pending state : states) {
                            if (state.failure != null) throw new CompletionException("Previous write failed for " + ids.getFirst(), state.failure);
                        }
                    }
                }
                try {
                    return action.call();
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            }, this.executor);
        } catch (RuntimeException failure) {
            operation = CompletableFuture.failedFuture(failure);
        }
        operation.whenComplete((value, failure) -> {
            synchronized (this.pending) {
                if (write) states.getFirst().failure = failure;
            }
            // Release the storage queue before invoking caller continuations.
            finished.complete(null);
            synchronized (this.pending) {
                for (int index = 0; index < ids.size(); index++) {
                    Pending state = states.get(index);
                    if (state.tail == finished && state.failure == null) this.pending.remove(ids.get(index), state);
                }
            }
            if (failure == null) result.complete(value);
            else result.completeExceptionally(failure);
        });
        // A caller's cancellation must not cancel accepted storage work.
        return result.copy();
    }

    public CompletableFuture<Void> flushAsync() {
        synchronized (this.pending) {
            return CompletableFuture.allOf(List.copyOf(this.pending.keySet()).stream()
                .map(id -> this.submit(List.of(id), false, () -> null))
                .toArray(CompletableFuture[]::new));
        }
    }

    public CompletableFuture<Void> closeAsync() {
        final List<Pending> states;
        final CompletableFuture<Void> drained;
        synchronized (this.pending) {
            this.closed = true;
            states = List.copyOf(this.pending.values());
            drained = CompletableFuture.allOf(states.stream().map(state -> state.tail).toArray(CompletableFuture[]::new));
        }
        return drained.thenRun(() -> {
            synchronized (this.pending) {
                for (Pending state : states) {
                    if (state.failure != null) throw new CompletionException("Failed to save all data before closing", state.failure);
                }
            }
        });
    }

    private static final class Pending {
        CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        @Nullable Throwable failure;
    }
}

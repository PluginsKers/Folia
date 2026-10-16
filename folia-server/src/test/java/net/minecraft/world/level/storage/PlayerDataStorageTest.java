package net.minecraft.world.level.storage;

import com.mojang.datafixers.DataFixer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.players.NameAndId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.bukkit.support.environment.VanillaFeature;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@VanillaFeature
class PlayerDataStorageTest {
    @TempDir
    Path directory;

    @Test
    void malformedInnerTagFallsBackButStrictReadFails() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        PlayerDataStorage storage = storage(executor);
        UUID id = UUID.randomUUID();
        try (var output = new java.util.zip.GZIPOutputStream(Files.newOutputStream(directory.resolve(id + ".dat")))) {
            output.write(new byte[] {10, 0, 0, 3, 0, 1, 'x', 0}); // truncated integer inside a valid gzip root
        }
        NbtIo.writeCompressed(tag(7), directory.resolve(id + ".dat_old"));
        var strict = storage.readCurrentAsync(id);
        executor.runNext();
        assertThrows(CompletionException.class, strict::join);
        var fallback = storage.loadWithMetadataAsync(new NameAndId(id, "malformed"), false);
        executor.runNext();
        assertEquals(tag(7), fallback.join().data().orElseThrow());
    }

    @Test
    void flushWaitsForAcceptedWritesWithoutClosingStorage() {
        ManualExecutor executor = new ManualExecutor();
        PlayerDataStorage storage = storage(executor);
        UUID id = UUID.randomUUID();
        CompletableFuture<Void> write = storage.saveSnapshotAsync(id, tag(1));
        CompletableFuture<Void> flush = storage.flushAsync();
        CompletableFuture<Void> next = storage.saveSnapshotAsync(id, tag(2));
        assertFalse(flush.isDone());
        executor.runNext();
        write.join();
        assertFalse(flush.isDone());
        executor.runNext();
        flush.join();
        assertFalse(next.isDone());
        executor.runNext();
        next.join();
    }

    @Test
    void snapshotsAndVerificationAreOrderedWithReads() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        PlayerDataStorage storage = storage(executor);
        UUID id = UUID.randomUUID();
        CompoundTag first = tag(1);
        CompletableFuture<Void> savedFirst = storage.saveSnapshotAsync(id, first);
        first.putInt("value", 99);
        CompletableFuture<?> read = storage.readCurrentAsync(id).thenAccept(data -> assertEquals(tag(1), data.orElseThrow()));
        CompletableFuture<Void> savedSecond = storage.saveSnapshotAsync(id, tag(2));

        assertEquals(1, executor.tasks.size());
        assertFalse(savedFirst.isDone());
        executor.runNext();
        assertTrue(savedFirst.isDone());
        assertEquals(tag(1), NbtIo.readCompressed(directory.resolve(id + ".dat"), NbtAccounter.unlimitedHeap()));
        executor.runNext();
        read.join();
        assertFalse(savedSecond.isDone());
        executor.runNext();
        savedSecond.join();
        assertEquals(tag(2), NbtIo.readCompressed(directory.resolve(id + ".dat"), NbtAccounter.unlimitedHeap()));
        assertEquals(tag(1), NbtIo.readCompressed(directory.resolve(id + ".dat_old"), NbtAccounter.unlimitedHeap()));
    }

    @Test
    void independentPlayersDoNotWaitAndCancellationDoesNotCancelAcceptedSave() {
        ManualExecutor executor = new ManualExecutor();
        PlayerDataStorage storage = storage(executor);
        UUID firstId = UUID.randomUUID();
        CompletableFuture<Void> cancelled = storage.saveSnapshotAsync(firstId, tag(1));
        CompletableFuture<Void> otherPlayer = storage.saveSnapshotAsync(UUID.randomUUID(), tag(2));
        cancelled.cancel(false);

        assertEquals(2, executor.tasks.size());
        executor.tasks.removeLast().run();
        otherPlayer.join();
        executor.runNext();
        CompletableFuture<?> read = storage.readCurrentAsync(firstId).thenAccept(data -> assertEquals(tag(1), data.orElseThrow()));
        executor.runNext();
        read.join();
    }

    @Test
    void failedReplacementPreventsStaleReadUntilAnotherSaveSucceeds() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        PlayerDataStorage storage = storage(executor);
        UUID id = UUID.randomUUID();
        Path target = Files.createDirectory(directory.resolve(id + ".dat"));
        Path obstacle = Files.writeString(target.resolve("occupied"), "keep");
        Path backup = Files.createDirectory(directory.resolve(id + ".dat_old"));
        Path backupObstacle = Files.writeString(backup.resolve("occupied"), "keep");
        CompletableFuture<Void> failed = storage.saveSnapshotAsync(id, tag(1));
        executor.runNext();
        assertThrows(CompletionException.class, failed::join);

        Files.delete(obstacle);
        Files.delete(target);
        Files.delete(backupObstacle);
        Files.delete(backup);
        NbtIo.writeCompressed(tag(0), directory.resolve(id + ".dat"));
        CompletableFuture<?> stale = storage.readCurrentAsync(id);
        CompletableFuture<?> login = storage.loadWithMetadataAsync(new NameAndId(id, "example"), false);
        executor.runNext();
        executor.runNext();
        assertThrows(CompletionException.class, stale::join);
        assertThrows(CompletionException.class, login::join);
        assertEquals(tag(0), NbtIo.readCompressed(directory.resolve(id + ".dat"), NbtAccounter.unlimitedHeap()));

        CompletableFuture<Void> recovered = storage.saveSnapshotAsync(id, tag(2));
        executor.runNext();
        recovered.join();
        CompletableFuture<?> current = storage.readCurrentAsync(id).thenAccept(data -> assertEquals(tag(2), data.orElseThrow()));
        executor.runNext();
        current.join();
    }

    @Test
    void strictReadNeverUsesBackupAndCorruptLoginNeverBecomesNewPlayer() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        PlayerDataStorage storage = storage(executor);
        UUID id = UUID.randomUUID();
        NbtIo.writeCompressed(tag(3), directory.resolve(id + ".dat_old"));
        CompletableFuture<?> absent = storage.readCurrentAsync(id).thenAccept(data -> assertTrue(data.isEmpty()));
        executor.runNext();
        absent.join();
        Files.writeString(directory.resolve(id + ".dat"), "broken");
        Files.writeString(directory.resolve(id + ".dat_old"), "broken backup");
        CompletableFuture<?> strict = storage.readCurrentAsync(id);
        CompletableFuture<?> login = storage.loadWithMetadataAsync(new NameAndId(id, "example"), false);
        executor.runNext();
        executor.runNext();
        assertThrows(CompletionException.class, strict::join);
        assertThrows(CompletionException.class, login::join);
    }

    @Test
    void shutdownDrainsAcceptedWritesAndRejectsNewRequests() {
        ManualExecutor executor = new ManualExecutor();
        PlayerDataStorage storage = storage(executor);
        UUID id = UUID.randomUUID();
        CompletableFuture<Void> saved = storage.saveSnapshotAsync(id, tag(1));
        CompletableFuture<Void> closed = storage.closeAsync();
        assertFalse(closed.isDone());
        assertThrows(CompletionException.class, () -> storage.saveSnapshotAsync(id, tag(2)).join());
        executor.runNext();
        saved.join();
        closed.join();
    }

    @Test
    void normalLoadRetainsBackupAndOfflineUuidMigrationAcrossBothLoginReads() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        PlayerDataStorage storage = storage(executor);
        UUID id = UUID.randomUUID();
        CompoundTag saved = tag(7);
        saved.putInt("DataVersion", ca.spottedleaf.dataconverter.minecraft.util.Version.getCurrentVersion());
        Files.writeString(directory.resolve(id + ".dat"), "broken");
        NbtIo.writeCompressed(saved, directory.resolve(id + ".dat_old"));
        CompletableFuture<PlayerDataStorage.LoadedData> backup = storage.loadWithMetadataAsync(new NameAndId(id, "backup"), false);
        executor.runNext();
        assertEquals(saved, backup.join().data().orElseThrow());

        NameAndId online = new NameAndId(UUID.randomUUID(), "migrated");
        UUID offline = net.minecraft.core.UUIDUtil.createOfflinePlayerUUID(online.name());
        NbtIo.writeCompressed(saved, directory.resolve(offline + ".dat"));
        CompletableFuture<PlayerDataStorage.LoadedData> initial = storage.loadWithMetadataAsync(online, true);
        CompletableFuture<PlayerDataStorage.LoadedData> finalRead = storage.loadWithMetadataAsync(online, true);
        assertEquals(1, executor.tasks.size());
        executor.runNext();
        executor.runNext();
        assertEquals(saved, initial.join().data().orElseThrow());
        assertEquals(saved, finalRead.join().data().orElseThrow());
        assertTrue(Files.exists(directory.resolve(online.id() + ".dat")));
        assertTrue(Files.exists(directory.resolve(offline + ".dat.offline-read")));
    }

    @Test
    void aCompletionMayReadTheSamePlayerWithoutHoldingUpTheStorageQueue() {
        try (var workers = java.util.concurrent.Executors.newCachedThreadPool()) {
            java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
            PlayerDataStorage storage = storage(task -> workers.execute(() -> {
                try {
                    writing.await();
                    task.run();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                }
            }));
            UUID id = UUID.randomUUID();
            CompletableFuture<Void> checked = storage.saveSnapshotAsync(id, tag(1)).thenRun(() -> {
                try {
                    assertEquals(tag(1), storage.readCurrentAsync(id).get(2, java.util.concurrent.TimeUnit.SECONDS).orElseThrow());
                } catch (Exception failure) {
                    throw new CompletionException(failure);
                }
            });
            writing.countDown();
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), checked::join);
        }
    }

    @Test
    void executorRejectionIsReportedAndDoesNotLoseTheShutdownBarrier() {
        PlayerDataStorage storage = storage(task -> { throw new java.util.concurrent.RejectedExecutionException(); });
        CompletableFuture<Void> saved = storage.saveSnapshotAsync(UUID.randomUUID(), tag(1));
        assertThrows(CompletionException.class, saved::join);
        assertThrows(CompletionException.class, () -> storage.closeAsync().join());
    }

    private PlayerDataStorage storage(Executor executor) {
        return new PlayerDataStorage(directory, mock(DataFixer.class), executor);
    }

    private static CompoundTag tag(int value) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("value", value);
        return tag;
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runNext() {
            tasks.removeFirst().run();
        }
    }
}

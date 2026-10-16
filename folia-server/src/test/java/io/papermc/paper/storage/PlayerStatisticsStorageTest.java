package io.papermc.paper.storage;

import com.google.gson.JsonObject;
import com.mojang.authlib.GameProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class PlayerStatisticsStorageTest {
    @TempDir Path directory;

    @Test
    void snapshotIsCopiedAndReadsAwaitCancelledAcceptedSave() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        PlayerStatisticsStorage storage = new PlayerStatisticsStorage(directory, executor);
        GameProfile player = profile("snapshot");
        JsonObject data = json(7);
        var save = storage.saveAsync(player.id(), data);
        data.addProperty("value", 90);
        save.cancel(false);
        var read = storage.readAsync(player, false);
        assertEquals(1, executor.tasks.size());
        executor.next();
        assertFalse(read.isDone());
        executor.next();
        assertEquals(json(7), read.join().orElseThrow());
        assertTrue(Files.readString(directory.resolve(player.id() + ".json")).contains("7"));
    }

    @Test
    void failedWriteBlocksOldJsonUntilSuccessfulAcceptedSave() throws Exception {
        PlayerStatisticsStorage storage = new PlayerStatisticsStorage(directory, Runnable::run);
        GameProfile player = profile("failure");
        Path file = directory.resolve(player.id() + ".json");
        Files.createDirectory(file);
        Path obstacle = Files.writeString(file.resolve("keep"), "occupied");
        assertThrows(CompletionException.class, storage.saveAsync(player.id(), json(1))::join);
        Files.delete(obstacle);
        Files.delete(file);
        Files.writeString(file, json(0).toString());
        assertThrows(CompletionException.class, storage.readAsync(player, false)::join);
        assertThrows(CompletionException.class, storage.readAsync(player, true)::join);
        storage.saveAsync(player.id(), json(2)).join();
        assertEquals(json(2), storage.readAsync(player, false).join().orElseThrow());
    }

    @Test
    void missingIsEmptyButMalformedAndUnreadableFilesFailWithoutWrites() throws Exception {
        PlayerStatisticsStorage storage = new PlayerStatisticsStorage(directory, Runnable::run);
        GameProfile player = profile("malformed");
        Path file = directory.resolve(player.id() + ".json");
        assertTrue(storage.readAsync(player, false).join().isEmpty());
        Files.writeString(file, "{broken");
        assertThrows(CompletionException.class, storage.readAsync(player, false)::join);
        assertEquals("{broken", Files.readString(file));
        Files.delete(file);
        Files.createDirectory(file);
        assertThrows(CompletionException.class, storage.readAsync(player, false)::join);
        Files.delete(file);
        storage.saveAsync(player.id(), json(3)).join();
        assertEquals(json(3), storage.readAsync(player, false).join().orElseThrow());
    }

    @Test
    void unknownNamesDoNotShareAFictitiousLegacyFile() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        PlayerStatisticsStorage storage = new PlayerStatisticsStorage(directory, executor);
        Files.writeString(directory.resolve(".json"), json(99).toString());
        var first = storage.readAsync(profile(""), false);
        var second = storage.readAsync(profile(""), false);
        assertEquals(2, executor.tasks.size());
        executor.next();
        executor.next();
        assertTrue(first.join().isEmpty());
        assertTrue(second.join().isEmpty());
    }

    @Test
    void snapshotDoesNotMigrateLegacyFileAndLoginDoes() throws Exception {
        PlayerStatisticsStorage storage = new PlayerStatisticsStorage(directory, Runnable::run);
        GameProfile player = profile("legacy");
        Path legacy = Files.writeString(directory.resolve("legacy.json"), json(4).toString());
        Path current = directory.resolve(player.id() + ".json");
        assertEquals(json(4), storage.readAsync(player, false).join().orElseThrow());
        assertTrue(Files.exists(legacy));
        assertFalse(Files.exists(current));
        assertEquals(json(4), storage.readAsync(player, true).join().orElseThrow());
        assertFalse(Files.exists(legacy));
        assertTrue(Files.exists(current));
        Files.writeString(legacy, json(99).toString());
        assertEquals(json(4), storage.readAsync(player, false).join().orElseThrow());
    }

    @Test
    void pendingSaveAcrossCounterLifetimesWinsOverLegacyFile() throws Exception {
        ManualExecutor executor = new ManualExecutor();
        PlayerStatisticsStorage storage = new PlayerStatisticsStorage(directory, executor);
        GameProfile player = profile("same-player");
        Files.writeString(directory.resolve("same-player.json"), json(0).toString());
        var first = storage.saveAsync(player.id(), json(1));
        var login = storage.readAsync(player, true);
        var next = storage.saveAsync(player.id(), json(2));
        executor.next();
        first.join();
        executor.next();
        assertEquals(json(1), login.join().orElseThrow());
        assertFalse(next.isDone());
        executor.next();
        next.join();
    }

    @Test
    void flushAndCloseDrainWithoutLosingIndependentPlayerProgress() {
        ManualExecutor executor = new ManualExecutor();
        PlayerStatisticsStorage storage = new PlayerStatisticsStorage(directory, executor);
        GameProfile first = profile("first");
        GameProfile second = profile("second");
        var firstSave = storage.saveAsync(first.id(), json(1));
        var secondSave = storage.saveAsync(second.id(), json(2));
        executor.tasks.removeLast().run();
        secondSave.join();
        assertFalse(firstSave.isDone());
        var flush = storage.flushAsync();
        var close = storage.closeAsync();
        assertThrows(CompletionException.class, storage.saveAsync(first.id(), json(3))::join);
        assertFalse(close.isDone());
        executor.next();
        executor.next();
        firstSave.join();
        flush.join();
        close.join();
    }

    @Test
    void storageDomainsKeepFailureBarriersIndependent() {
        OrderedIo<UUID> nbt = new OrderedIo<>(Runnable::run);
        OrderedIo<UUID> stats = new OrderedIo<>(Runnable::run);
        UUID id = UUID.randomUUID();
        assertThrows(CompletionException.class, stats.submit(List.of(id), true, () -> { throw new java.io.IOException("full"); })::join);
        assertEquals(4, nbt.submit(List.of(id), false, () -> 4).join());
        assertThrows(CompletionException.class, stats.closeAsync()::join);
        nbt.closeAsync().join();
    }

    private static GameProfile profile(String name) {
        return new GameProfile(UUID.randomUUID(), name);
    }

    private static JsonObject json(int value) {
        JsonObject data = new JsonObject();
        data.addProperty("value", value);
        return data;
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void next() { tasks.removeFirst().run(); }
    }
}

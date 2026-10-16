package io.papermc.paper.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import net.minecraft.util.FileUtil;
import net.minecraft.util.StrictJsonParser;

/** One queue for every instance accessing the world's statistics files. */
public final class PlayerStatisticsStorage {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path directory;
    private final OrderedIo<Path> io;

    public PlayerStatisticsStorage(final Path directory, final Executor executor) {
        this.directory = directory;
        this.io = new OrderedIo<>(executor);
    }

    public CompletableFuture<Optional<JsonElement>> readAsync(final GameProfile profile, final boolean migrate) {
        Path current = this.file(profile.id());
        String legacyName = profile.name() + ".json";
        Path legacy = !profile.name().isEmpty() && FileUtil.isValidPathSegment(legacyName) ? this.directory.resolve(legacyName) : current;
        List<Path> keys = current.equals(legacy) ? List.of(current) : List.of(current, legacy);
        return this.io.submit(keys, false, () -> {
            Path source = Files.notExists(current) ? legacy : current;
            if (Files.notExists(source)) return Optional.empty();
            JsonElement data = read(source);
            if (migrate && !source.equals(current)) {
                try {
                    Files.move(source, current);
                } catch (IOException failure) {
                    LOGGER.warn("Failed to migrate statistics from {} to {}", source, current, failure);
                }
            }
            return Optional.of(data);
        });
    }

    public CompletableFuture<Void> saveAsync(final UUID id, final JsonElement data) {
        JsonElement snapshot = data.deepCopy();
        Path current = this.file(id);
        return this.io.submit(List.of(current), true, () -> {
            FileUtil.createDirectoriesSafe(this.directory);
            Path temporary = Files.createTempFile(this.directory, id + "-", ".json");
            try {
                try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                    GSON.toJson(snapshot, writer);
                }
                try {
                    Files.move(temporary, current, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, current, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!snapshot.equals(read(current))) throw new IOException("Statistics verification failed for " + id);
                return null;
            } finally {
                Files.deleteIfExists(temporary);
            }
        });
    }

    public CompletableFuture<Void> failedSave(final UUID id, final Exception failure) {
        return this.io.submit(List.of(this.file(id)), true, () -> { throw failure; });
    }

    public CompletableFuture<Void> flushAsync() {
        return this.io.flushAsync();
    }

    public CompletableFuture<Void> closeAsync() {
        return this.io.closeAsync();
    }

    private Path file(final UUID id) {
        return this.directory.resolve(id + ".json");
    }

    private static JsonElement read(final Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return StrictJsonParser.parse(reader);
        }
    }
}

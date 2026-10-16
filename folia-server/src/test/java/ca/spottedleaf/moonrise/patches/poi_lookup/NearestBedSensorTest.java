package ca.spottedleaf.moonrise.patches.poi_lookup;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import com.mojang.datafixers.util.Pair;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.AcquirePoi;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.sensing.NearestBedSensor;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.pathfinder.Path;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class NearestBedSensorTest {
    private static final class Sensor extends NearestBedSensor {
        void run(ServerLevel level, Mob body) { super.doTick(level, body); }
    }

    private static final class Attempt {
        final PoiAccess.Search search;
        final Predicate<Mob> valid;
        final Consumer<List<PoiRecord>> complete;
        final Runnable finished;

        Attempt(org.mockito.invocation.InvocationOnMock call) {
            search = call.getArgument(1);
            valid = call.getArgument(2);
            complete = call.getArgument(3);
            finished = call.getArgument(4);
        }

        boolean resume(LongFunction<Optional<PoiSection>> provider) {
            if (!search.advance(provider)) return false;
            try { complete.accept(search.result()); }
            finally { finished.run(); }
            return true;
        }
    }

    private static final class Fixture {
        final Sensor sensor = new Sensor();
        final Mob body = mock(Mob.class);
        final ServerLevel level = mock(ServerLevel.class);
        final PoiManager manager = mock(PoiManager.class);
        final Brain<Mob> brain = new Brain<>();
        final Map<Long, PoiSection> sections = new HashMap<>();
        final Holder<PoiType> home = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME);

        Fixture() throws Exception {
            var register = Brain.class.getDeclaredMethod("registerMemory", MemoryModuleType.class);
            register.setAccessible(true);
            register.invoke(brain, MemoryModuleType.NEAREST_BED);
            doReturn(brain).when(body).getBrain();
            when(body.isBaby()).thenReturn(true);
            when(body.blockPosition()).thenReturn(BlockPos.ZERO);
            when(level.getGameTime()).thenReturn(100L);
            when(level.getRandom()).thenReturn(mock(RandomSource.class));
            when(level.getPoiManager()).thenReturn(manager);
            var height = SectionStorage.class.getDeclaredField("levelHeightAccessor");
            height.setAccessible(true);
            height.set(manager, LevelHeightAccessor.create(-64, 128));
            when(manager.getType(any())).thenAnswer(call -> {
                BlockPos pos = call.getArgument(0);
                return section(CoordinateUtils.getChunkSectionKey(pos)).flatMap(value -> value.getType(pos));
            });
            when(manager.getOrLoad(anyLong())).thenAnswer(call -> section(call.getArgument(0)));
            when(manager.findAllWithType(any(), any(), any(), anyInt(), any())).thenCallRealMethod();
        }

        BlockPos add(int x, int y, int z) {
            BlockPos pos = new BlockPos(x, y, z);
            sections.computeIfAbsent(CoordinateUtils.getChunkSectionKey(pos), key -> new PoiSection(() -> {})).add(pos, home);
            return pos;
        }

        Optional<PoiSection> section(long key) { return Optional.ofNullable(sections.get(key)); }

        Long2LongMap cache() throws Exception {
            var field = NearestBedSensor.class.getDeclaredField("batchCache");
            field.setAccessible(true);
            return (Long2LongMap) field.get(sensor);
        }

        int tried() throws Exception {
            var field = NearestBedSensor.class.getDeclaredField("triedCount");
            field.setAccessible(true);
            return field.getInt(sensor);
        }
    }

    @Test
    void coldSearchDoesNotRunSynchronousLookupOrStartAnotherAttempt() throws Exception {
        Fixture f = new Fixture();
        try (var tasks = mockStatic(PoiSearchTask.class); var paths = mockStatic(AcquirePoi.class)) {
            Attempt[] pending = new Attempt[1];
            tasks.when(() -> PoiSearchTask.start(any(), any(), any(), any(), any())).thenAnswer(call -> {
                pending[0] = new Attempt(call);
                assertFalse(pending[0].resume(key -> null));
                return null;
            });
            f.sensor.run(f.level, f.body);
            assertNotNull(pending[0]);
            for (int i = 0; i < 40; i++) f.sensor.run(f.level, f.body);
            tasks.verify(() -> PoiSearchTask.start(any(), any(), any(), any(), any()), times(1));
            verify(f.manager, never()).findAllWithType(any(), any(), any(), anyInt(), any());
            verify(f.manager, never()).getOrLoad(anyLong());
            paths.verifyNoInteractions();
            assertTrue(pending[0].resume(f::section));
            f.sensor.run(f.level, f.body);
            tasks.verify(() -> PoiSearchTask.start(any(), any(), any(), any(), any()), times(2));
        }
    }

    @Test
    void suspendedTraversalMatchesLegacyCandidateOrderCacheAndAttemptLimit() throws Exception {
        Fixture f = new Fixture();
        f.add(-16, 0, -16); f.add(0, 0, -16); f.add(16, 0, -16);
        f.add(-16, 0, 0); f.add(0, 0, 0); f.add(16, 0, 0); f.add(48, 0, 0);
        f.add(49, 0, 0); f.add(0, 49, 0);
        Long2LongMap legacyCache = new Long2LongOpenHashMap();
        AtomicInteger legacyTried = new AtomicInteger();
        Set<Pair<Holder<PoiType>, BlockPos>> expected = f.manager.findAllWithType(
            type -> type.is(PoiTypes.HOME), pos -> {
                if (legacyCache.containsKey(pos.asLong())) return false;
                if (legacyTried.incrementAndGet() >= 5) return false;
                legacyCache.put(pos.asLong(), 140L);
                return true;
            }, BlockPos.ZERO, AcquirePoi.SCAN_RANGE, PoiManager.Occupancy.ANY).collect(Collectors.toSet());
        assertEquals(4, expected.size(), "Preserve the original ++triedCount >= 5 boundary");
        assertEquals(7, legacyTried.get());
        try (var tasks = mockStatic(PoiSearchTask.class); var paths = mockStatic(AcquirePoi.class)) {
            tasks.when(() -> PoiSearchTask.start(any(), any(), any(), any(), any())).thenAnswer(call -> {
                Attempt attempt = new Attempt(call);
                Set<Long> ready = new HashSet<>();
                int resumes = 0;
                while (!attempt.resume(key -> ready.add(key) ? null : f.section(key))) {
                    assertTrue(++resumes < 1000);
                    paths.verifyNoInteractions();
                }
                return null;
            });
            f.sensor.run(f.level, f.body);
            paths.verify(() -> AcquirePoi.findPathToPois(f.body, expected));
            assertEquals(legacyCache, f.cache());
            assertEquals(legacyTried.get(), f.tried());
        }
    }

    @Test
    void grownMobInvalidatesPendingSearchAndAdultDoesNotStart() throws Exception {
        Fixture f = new Fixture();
        try (var tasks = mockStatic(PoiSearchTask.class)) {
            tasks.when(() -> PoiSearchTask.start(any(), any(), any(), any(), any())).thenAnswer(call -> {
                Attempt attempt = new Attempt(call);
                assertTrue(attempt.valid.test(f.body));
                when(f.body.isBaby()).thenReturn(false);
                assertFalse(attempt.valid.test(f.body));
                attempt.finished.run();
                return null;
            });
            f.sensor.run(f.level, f.body);
            f.sensor.run(f.level, f.body);
            tasks.verify(() -> PoiSearchTask.start(any(), any(), any(), any(), any()), times(1));
            assertFalse(f.brain.hasMemoryValue(MemoryModuleType.NEAREST_BED));
        }
    }

    @Test
    void completionRechecksRemovedBedAndDoesNotWriteStaleMemory() throws Exception {
        Fixture f = new Fixture();
        BlockPos bed = f.add(0, 0, 0);
        try (var tasks = mockStatic(PoiSearchTask.class); var paths = mockStatic(AcquirePoi.class)) {
            tasks.when(() -> PoiSearchTask.start(any(), any(), any(), any(), any())).thenAnswer(call -> {
                Attempt attempt = new Attempt(call);
                assertTrue(attempt.search.advance(f::section));
                f.sections.clear();
                attempt.complete.accept(attempt.search.result());
                attempt.finished.run();
                return null;
            });
            f.sensor.run(f.level, f.body);
            paths.verify(() -> AcquirePoi.findPathToPois(f.body, Set.of()));
            assertFalse(f.brain.hasMemoryValue(MemoryModuleType.NEAREST_BED));
            assertTrue(f.cache().containsKey(bed.asLong()));
        }
    }

    @Test
    void reachableBedUpdatesMemoryAndCompletionFailureReleasesGuard() throws Exception {
        Fixture f = new Fixture();
        BlockPos bed = f.add(0, 0, 0);
        Path path = mock(Path.class);
        when(path.canReach()).thenReturn(true);
        when(path.getTarget()).thenReturn(bed);
        try (var tasks = mockStatic(PoiSearchTask.class); var paths = mockStatic(AcquirePoi.class)) {
            tasks.when(() -> PoiSearchTask.start(any(), any(), any(), any(), any())).thenAnswer(call -> {
                new Attempt(call).resume(f::section);
                return null;
            });
            paths.when(() -> AcquirePoi.findPathToPois(any(), anySet())).thenThrow(new IllegalStateException("path failure"));
            assertThrows(IllegalStateException.class, () -> f.sensor.run(f.level, f.body));
            f.cache().clear();
            paths.when(() -> AcquirePoi.findPathToPois(any(), anySet())).thenReturn(path);
            f.sensor.run(f.level, f.body);
            assertEquals(Optional.of(bed), f.brain.getMemory(MemoryModuleType.NEAREST_BED));
            tasks.verify(() -> PoiSearchTask.start(any(), any(), any(), any(), any()), times(2));
        }
    }

    @Test
    void failedPathPrunesOnlyExpiredCacheWhenOriginalAttemptLimitAllows() throws Exception {
        Fixture f = new Fixture();
        long expired = new BlockPos(8, 0, 8).asLong();
        long boundary = new BlockPos(9, 0, 9).asLong();
        f.cache().put(expired, 99L);
        f.cache().put(boundary, 100L);
        try (var tasks = mockStatic(PoiSearchTask.class); var paths = mockStatic(AcquirePoi.class)) {
            tasks.when(() -> PoiSearchTask.start(any(), any(), any(), any(), any())).thenAnswer(call -> {
                new Attempt(call).resume(f::section);
                return null;
            });
            f.sensor.run(f.level, f.body);
            assertFalse(f.cache().containsKey(expired));
            assertTrue(f.cache().containsKey(boundary));
            assertEquals(0, f.tried());
        }
    }
}

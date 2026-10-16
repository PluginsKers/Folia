package io.papermc.paper.threadedregions;

import io.papermc.paper.configuration.WorldConfiguration;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.CatSpawner;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class CatSpawnerLifecycleTest {
    private static final class Fixture {
        final ServerLevel world = mock(ServerLevel.class);
        final RegionizedTaskQueue.WorldRegionTaskData queue = new RegionizedTaskQueue.WorldRegionTaskData(world);

        Fixture() throws Exception {
            WorldConfiguration config = mock(WorldConfiguration.class);
            config.hopper = mock(WorldConfiguration.Hopper.class);
            when(world.paperConfig()).thenReturn(config);
            field(ServerLevel.class, "taskQueueRegionData", world, queue);
        }

        RegionizedWorldData data() { return new RegionizedWorldData(world, mock(TickRegions.TickRegionData.class)); }

        ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region(RegionizedWorldData data) throws Exception {
            var tickData = mock(TickRegions.TickRegionData.class);
            field(TickRegions.TickRegionData.class, "world", tickData, world);
            when(tickData.getRegionizedData(world.worldRegionData)).thenReturn(data);
            @SuppressWarnings("unchecked")
            ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region = mock(ThreadedRegionizer.ThreadedRegion.class);
            when(region.getData()).thenReturn(tickData);
            return region;
        }

        void shutdownVisits(List<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> regions) throws Exception {
            @SuppressWarnings("unchecked")
            ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser = mock(ThreadedRegionizer.class);
            field(ServerLevel.class, "regioniser", world, regioniser);
            doAnswer(call -> {
                Consumer<ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData>> visit = call.getArgument(0);
                regions.forEach(visit);
                return null;
            }).when(regioniser).computeForAllRegionsUnsynchronised(any());
        }
    }

    private static void field(Class<?> type, String name, Object object, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(object, value);
    }

    @Test
    void mergeCancelsBothAttemptsOutsideRegionLockAndPreservesMaximumDelay() throws Exception {
        try (var global = mockStatic(RegionizedServer.class)) {
            global.when(RegionizedServer::getInstance).thenReturn(mock(RegionizedServer.class));
            Fixture f = new Fixture();
            RegionizedWorldData from = f.data(), into = f.data();
            var old = mock(CatSpawner.SpawnAttempt.class);
            var destination = mock(CatSpawner.SpawnAttempt.class);
            from.catSpawnAttempt = old;
            into.catSpawnAttempt = destination;
            from.catSpawnerNextTick = 1100;
            into.catSpawnerNextTick = 100;
            RegionizedWorldData.REGION_CALLBACK.merge(from, into, 20L);
            assertNull(from.catSpawnAttempt);
            assertNull(into.catSpawnAttempt);
            assertEquals(1100, into.catSpawnerNextTick);
            verifyNoInteractions(old, destination);
            // A destroyed source cannot enqueue its former attempt a second time.
            new TickRegions().onRegionDestroy(f.region(from));
            f.queue.drainGlobalChunkTasks();
            verify(old).close();
            verify(destination).close();
            assertFalse(f.queue.hasGlobalChunkTasks());
        }
    }

    @Test
    void splitDoesNotCopyAttemptToChildrenAndDestroyDoesNotDoubleClose() throws Exception {
        try (var global = mockStatic(RegionizedServer.class)) {
            global.when(RegionizedServer::getInstance).thenReturn(mock(RegionizedServer.class));
            Fixture f = new Fixture();
            RegionizedWorldData from = f.data(), left = f.data(), right = f.data();
            var attempt = mock(CatSpawner.SpawnAttempt.class);
            from.catSpawnAttempt = attempt;
            from.catSpawnerNextTick = 777;
            Long2ReferenceOpenHashMap<RegionizedWorldData> children = new Long2ReferenceOpenHashMap<>();
            children.put(0L, left);
            children.put(1L, right);
            RegionizedWorldData.REGION_CALLBACK.split(from, 4, children, new ReferenceOpenHashSet<>(List.of(left, right)));
            assertNull(from.catSpawnAttempt);
            assertNull(left.catSpawnAttempt);
            assertNull(right.catSpawnAttempt);
            assertEquals(777, left.catSpawnerNextTick);
            assertEquals(777, right.catSpawnerNextTick);
            new TickRegions().onRegionDestroy(f.region(from));
            verifyNoInteractions(attempt);
            f.queue.drainGlobalChunkTasks();
            verify(attempt).close();
            assertFalse(f.queue.hasGlobalChunkTasks());
        }
    }

    @Test
    void destroyQueuesReleaseWithoutCreatingRegionData() throws Exception {
        try (var global = mockStatic(RegionizedServer.class)) {
            global.when(RegionizedServer::getInstance).thenReturn(mock(RegionizedServer.class));
            Fixture f = new Fixture();
            RegionizedWorldData data = f.data();
            var attempt = mock(CatSpawner.SpawnAttempt.class);
            data.catSpawnAttempt = attempt;
            var region = f.region(data);
            new TickRegions().onRegionDestroy(region);
            assertNull(data.catSpawnAttempt);
            verifyNoInteractions(attempt);
            verify(region.getData(), never()).getOrCreateRegionizedData(any());
            f.queue.drainGlobalChunkTasks();
            verify(attempt).close();
        }
    }

    @Test
    void shutdownReleasesActiveAndAlreadyDetachedAttemptsDespiteEarlierQueueFailure() throws Exception {
        try (var global = mockStatic(RegionizedServer.class)) {
            global.when(RegionizedServer::getInstance).thenReturn(mock(RegionizedServer.class));
            Fixture f = new Fixture();
            RegionizedWorldData destroyed = f.data(), active = f.data();
            var old = mock(CatSpawner.SpawnAttempt.class);
            var current = mock(CatSpawner.SpawnAttempt.class);
            destroyed.catSpawnAttempt = old;
            active.catSpawnAttempt = current;
            f.queue.pushGlobalChunkTask(() -> { throw new IllegalStateException("earlier task failed"); });
            new TickRegions().onRegionDestroy(f.region(destroyed));
            f.shutdownVisits(List.of(f.region(active)));
            RegionShutdownThread.finishPendingCatSpawns(f.world);
            assertNull(active.catSpawnAttempt);
            verify(old).close();
            verify(current).close();
            assertFalse(f.queue.hasGlobalChunkTasks());
            RegionShutdownThread.finishPendingCatSpawns(f.world);
            verifyNoMoreInteractions(old, current);
        }
    }
}

package ca.spottedleaf.moonrise.patches.poi_lookup;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import io.papermc.paper.threadedregions.EntityScheduler;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftMob;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class PoiSearchTaskTest {
    private static final class Fixture {
        final Mob body = mock(Mob.class);
        final ServerLevel level = mock(ServerLevel.class);
        final ChunkHolderManager manager = mock(ChunkHolderManager.class);
        final ChunkHolderManager.PoiChunkLease lease = mock(ChunkHolderManager.PoiChunkLease.class);
        final EntityScheduler scheduler = mock(EntityScheduler.class);
        final AtomicBoolean valid = new AtomicBoolean(true);
        final AtomicInteger commits = new AtomicInteger(), finishes = new AtomicInteger();
        Consumer<Entity> owner, retired;

        Fixture() throws Exception {
            Brain<?> brain = mock(Brain.class);
            doReturn(brain).when(body).getBrain();
            when(body.level()).thenReturn(level);
            when(body.blockPosition()).thenReturn(BlockPos.ZERO);
            when(body.isAlive()).thenReturn(true);
            CraftMob craft = mock(CraftMob.class);
            when(body.getBukkitEntity()).thenReturn(craft);
            var schedulerField = CraftEntity.class.getField("taskScheduler");
            schedulerField.setAccessible(true);
            schedulerField.set(craft, scheduler);
            ChunkTaskScheduler tasks = mock(ChunkTaskScheduler.class);
            var managerField = ChunkTaskScheduler.class.getField("chunkHolderManager");
            managerField.setAccessible(true);
            managerField.set(tasks, manager);
            when(level.moonrise$getChunkTaskScheduler()).thenReturn(tasks);
            when(manager.acquirePoiChunk(0, 0)).thenReturn(lease);
            when(scheduler.schedule(any(), any(), eq(1L))).thenAnswer(call -> {
                owner = call.getArgument(0);
                retired = call.getArgument(1);
                return true;
            });
        }

        void ready() {
            PoiChunk chunk = mock(PoiChunk.class);
            when(chunk.getSectionForVanilla(0)).thenReturn(Optional.empty());
            when(lease.getIfReady()).thenReturn(chunk);
        }

        void start() {
            PoiAccess.Search cursor = new PoiAccess.Search() {
                public boolean advance(java.util.function.LongFunction<Optional<PoiSection>> sections) {
                    return sections.apply(CoordinateUtils.getChunkSectionKey(0, 0, 0)) != null;
                }
                public List<PoiRecord> result() { return List.of(); }
            };
            PoiSearchTask.start(body, cursor, mob -> valid.get(), records -> commits.incrementAndGet(), finishes::incrementAndGet);
        }
    }

    @Test
    void closeFailureStillReleasesOtherLeasesAndAttemptGuard() throws Exception {
        try (var tick = mockStatic(TickThread.class)) {
            tick.when(() -> TickThread.isTickThreadFor(any(), anyInt(), anyInt())).thenReturn(true);
            Fixture f = new Fixture();
            f.ready();
            var otherLease = mock(ChunkHolderManager.PoiChunkLease.class);
            when(f.manager.acquirePoiChunk(1, 0)).thenReturn(otherLease);
            doThrow(new IllegalStateException("cancel failed")).when(f.lease).close();
            PoiAccess.Search cursor = new PoiAccess.Search() {
                public boolean advance(java.util.function.LongFunction<Optional<PoiSection>> sections) {
                    sections.apply(CoordinateUtils.getChunkSectionKey(0, 0, 0));
                    sections.apply(CoordinateUtils.getChunkSectionKey(1, 0, 0));
                    return false;
                }
                public List<PoiRecord> result() { return List.of(); }
            };
            PoiSearchTask.start(f.body, cursor, mob -> f.valid.get(), records -> f.commits.incrementAndGet(), f.finishes::incrementAndGet);
            f.valid.set(false);
            assertThrows(IllegalStateException.class, () -> f.owner.accept(f.body));
            verify(f.lease).close();
            verify(otherLease).close();
            assertEquals(1, f.finishes.get());
            assertEquals(0, f.commits.get());
        }
    }

    @Test
    void pendingReadReturnsAndResumesOnceOnOwnerWithoutDuplicatingLease() throws Exception {
        try (var tick = mockStatic(TickThread.class)) {
            tick.when(() -> TickThread.isTickThreadFor(any(), anyInt(), anyInt())).thenReturn(true);
            Fixture f = new Fixture();
            f.start();
            assertEquals(0, f.commits.get());
            assertEquals(0, f.finishes.get());
            verify(f.lease, never()).close();
            f.owner.accept(f.body);
            verify(f.manager).acquirePoiChunk(0, 0);
            f.ready();
            f.owner.accept(f.body);
            assertEquals(1, f.commits.get());
            assertEquals(1, f.finishes.get());
            verify(f.lease).close();
        }
    }

    @Test
    void movementMemoryBrainDeathAndDisabledAiCancelWithoutCommit() throws Exception {
        try (var tick = mockStatic(TickThread.class)) {
            tick.when(() -> TickThread.isTickThreadFor(any(), anyInt(), anyInt())).thenReturn(true);
            for (int condition = 0; condition < 5; ++condition) {
                Fixture f = new Fixture();
                f.start();
                switch (condition) {
                    case 0 -> when(f.body.blockPosition()).thenReturn(new BlockPos(1, 0, 0));
                    case 1 -> f.valid.set(false);
                    case 2 -> { Brain<?> replacement = mock(Brain.class); doReturn(replacement).when(f.body).getBrain(); }
                    case 3 -> when(f.body.isAlive()).thenReturn(false);
                    case 4 -> when(f.body.isNoAi()).thenReturn(true);
                }
                f.ready();
                f.owner.accept(f.body);
                assertEquals(0, f.commits.get());
                assertEquals(1, f.finishes.get());
                verify(f.lease).close();
            }
        }
    }

    @Test
    void regionOwnershipLossAndSchedulerRejectionReleaseLeases() throws Exception {
        try (var tick = mockStatic(TickThread.class)) {
            tick.when(() -> TickThread.isTickThreadFor(any(), anyInt(), anyInt())).thenReturn(true);
            Fixture f = new Fixture();
            f.start();
            tick.when(() -> TickThread.isTickThreadFor(any(), anyInt(), anyInt())).thenReturn(false);
            f.owner.accept(f.body);
            assertEquals(0, f.commits.get());
            verify(f.lease).close();
            tick.when(() -> TickThread.isTickThreadFor(any(), anyInt(), anyInt())).thenReturn(true);
            Fixture rejected = new Fixture();
            when(rejected.scheduler.schedule(any(), any(), eq(1L))).thenReturn(false);
            rejected.start();
            assertEquals(1, rejected.finishes.get());
            verify(rejected.lease).close();
        }
    }

    @Test
    void retirementReleasesOutsideCriticalCallbackAndNeverCommits() throws Exception {
        try (var tick = mockStatic(TickThread.class)) {
            tick.when(() -> TickThread.isTickThreadFor(any(), anyInt(), anyInt())).thenReturn(true);
            Fixture f = new Fixture();
            CountDownLatch released = new CountDownLatch(1);
            Thread caller = Thread.currentThread();
            doAnswer(call -> { assertNotSame(caller, Thread.currentThread()); released.countDown(); return null; }).when(f.lease).close();
            f.start();
            f.retired.accept(f.body);
            assertTrue(released.await(5, TimeUnit.SECONDS));
            assertEquals(0, f.commits.get());
        }
    }
}

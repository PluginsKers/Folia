package ca.spottedleaf.moonrise.patches.poi_lookup;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import io.papermc.paper.threadedregions.RegionizedServer;
import io.papermc.paper.threadedregions.RegionizedTaskQueue;
import io.papermc.paper.threadedregions.ThreadedRegionizer;
import io.papermc.paper.threadedregions.TickRegionScheduler;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.debug.LevelDebugSynchronizers;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class PoiReleaseTest {
    private static final BlockPos POS = new BlockPos(32, 64, 48);

    private static void set(Object target, Class<?> type, String name, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Fixture implements AutoCloseable {
        final ServerLevel world = mock(ServerLevel.class);
        final PoiManager pois = mock(PoiManager.class, CALLS_REAL_METHODS);
        final ChunkHolderManager manager = mock(ChunkHolderManager.class);
        final ChunkHolderManager.PoiChunkLease lease = mock(ChunkHolderManager.PoiChunkLease.class);
        final PoiChunk chunk = mock(PoiChunk.class);
        final LevelDebugSynchronizers debug = mock(LevelDebugSynchronizers.class);
        final Holder<PoiType> type = mock(Holder.Reference.class);
        final Set<?> pending = ConcurrentHashMap.newKeySet();
        final List<PrioritisedExecutor.PrioritisedTask> tasks = new ArrayList<>();
        final List<Runnable> ready = new ArrayList<>();
        final MockedStatic<TickThread> tick = mockStatic(TickThread.class);
        final MockedStatic<TickRegionScheduler> regions = mockStatic(TickRegionScheduler.class);
        final MockedStatic<RegionizedServer> server = mockStatic(RegionizedServer.class);

        Fixture() throws Exception {
            set(pois, PoiManager.class, "world", world);
            set(pois, PoiManager.class, "pendingReleases", pending);
            ChunkTaskScheduler scheduler = mock(ChunkTaskScheduler.class);
            set(scheduler, ChunkTaskScheduler.class, "chunkHolderManager", manager);
            when(world.moonrise$getChunkTaskScheduler()).thenReturn(scheduler);
            when(world.debugSynchronizers()).thenReturn(debug);
            when(manager.acquirePoiChunk(2, 3)).thenReturn(lease);
            doReturn(Optional.of(type)).when(pois).getType(POS);
            doReturn(true).when(pois).release(POS);
            doAnswer(call -> { ready.add(call.getArgument(0)); return null; }).when(lease).whenReady(any());

            // Keep the existing task's real execute/cancel once semantics; only region queue dispatch is gated.
            set(world, ServerLevel.class, "regioniser", mock(ThreadedRegionizer.class));
            set(world, ServerLevel.class, "taskQueueRegionData", new RegionizedTaskQueue.WorldRegionTaskData(world));
            RegionizedTaskQueue queue = mock(RegionizedTaskQueue.class);
            RegionizedTaskQueue realQueue = new RegionizedTaskQueue();
            when(queue.createTickTaskQueue(eq(world), eq(2), eq(3), any())).thenAnswer(call -> {
                var task = spy(realQueue.createTickTaskQueue(world, 2, 3, call.getArgument(3)));
                doReturn(true).when(task).queue();
                tasks.add(task);
                return task;
            });
            RegionizedServer instance = mock(RegionizedServer.class);
            set(instance, RegionizedServer.class, "taskQueue", queue);
            server.when(RegionizedServer::getInstance).thenReturn(instance);
            owner(true);
        }

        void owner(boolean owned) {
            regions.when(TickRegionScheduler::getCurrentRegion).thenReturn(owned ? mock(ThreadedRegionizer.ThreadedRegion.class) : null);
            tick.when(() -> TickThread.isTickThreadFor(world, 2, 3)).thenReturn(owned);
            tick.when(() -> TickThread.isTickThreadFor(world, POS)).thenReturn(owned);
        }

        void loaded() { when(lease.getIfReady()).thenReturn(chunk); }
        void accept(boolean updateWhenAbsent) { pois.releaseWhenLoaded(POS, ignored -> true, updateWhenAbsent); }
        void shutdown() { tick.when(TickThread::isShutdownThread).thenReturn(true); }

        public void close() {
            server.close(); regions.close(); tick.close();
        }
    }

    @Test
    void hotOwnerReleasesImmediatelyButAbsentAndNonMatchingTypesRetainTheirSemantics() throws Exception {
        try (Fixture f = new Fixture()) {
            when(f.manager.getPoiChunkIfLoaded(2, 3, true)).thenReturn(f.chunk);
            f.accept(false);
            verify(f.pois).release(POS);
            verify(f.debug).updatePoi(POS);
            verify(f.manager, never()).acquirePoiChunk(anyInt(), anyInt());
            clearInvocations(f.pois, f.debug);
            f.pois.releaseWhenLoaded(POS, ignored -> false, false);
            verify(f.pois, never()).release(POS);
            verifyNoInteractions(f.debug);
            doReturn(Optional.empty()).when(f.pois).getType(POS);
            f.accept(true);
            verify(f.pois, never()).release(POS);
            verify(f.debug).updatePoi(POS);
        }
    }

    @Test
    void coldReleaseReturnsBeforeReadAndOnlyChangesPoiWhenTheOwnerTaskExecutes() throws Exception {
        try (Fixture f = new Fixture()) {
            f.accept(false);
            assertEquals(1, f.pending.size());
            verify(f.pois, never()).getType(POS);
            verify(f.chunk, never()).load();
            f.loaded();
            f.ready.getFirst().run();
            verify(f.tasks.getFirst()).queue();
            verify(f.pois, never()).release(POS);
            assertTrue(f.tasks.getFirst().execute());
            assertFalse(f.tasks.getFirst().execute());
            verify(f.chunk).load();
            verify(f.pois).release(POS);
            verify(f.lease).close();
            assertTrue(f.pending.isEmpty());
        }
    }

    @Test
    void currentTypeIsRecheckedAndReadyReadCanFinishInlineOnItsOwner() throws Exception {
        try (Fixture f = new Fixture()) {
            f.loaded();
            when(f.lease.isReady()).thenReturn(true);
            doReturn(Optional.empty()).when(f.pois).getType(POS);
            f.accept(false);
            verify(f.chunk).load();
            verify(f.pois, never()).release(POS);
            verifyNoInteractions(f.debug);
            verify(f.lease, never()).whenReady(any());
            verify(f.lease).close();
            assertTrue(f.pending.isEmpty());
        }
    }

    @Test
    void shutdownWithoutCurrentRegionQueuesEvenAlreadyLoadedPoi() throws Exception {
        try (Fixture f = new Fixture()) {
            f.owner(false);
            f.shutdown();
            // The existing shutdown exemption alone is not ownership of region state.
            f.tick.when(() -> TickThread.isTickThreadFor(f.world, 2, 3)).thenReturn(true);
            when(f.manager.getPoiChunkIfLoaded(2, 3, true)).thenReturn(f.chunk);
            when(f.lease.isReady()).thenReturn(true);
            f.loaded();
            f.accept(false);
            verify(f.pois, never()).getType(POS);
            verify(f.chunk, never()).load();
            f.ready.getFirst().run();
            f.pois.awaitPendingReleases(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            assertThrows(IllegalStateException.class, f.pois::finishPendingReleases);
            f.owner(true);
            f.pois.finishPendingReleases();
            f.pois.finishPendingReleases();
            verify(f.pois).release(POS);
            verify(f.lease).close();
            assertTrue(f.pending.isEmpty());
        }
    }

    @Test
    void shutdownWaitsForQueueHandoffAfterReadReadyAndDrainsOnlyMatchingRegion() throws Exception {
        try (Fixture f = new Fixture()) {
            f.accept(false);
            f.loaded();
            CountDownLatch queueEntered = new CountDownLatch(1), queueAllowed = new CountDownLatch(1);
            CountDownLatch waiting = new CountDownLatch(1), finished = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            doAnswer(call -> {
                queueEntered.countDown();
                assertTrue(queueAllowed.await(5, TimeUnit.SECONDS));
                return true;
            }).when(f.tasks.getFirst()).queue();
            Thread callback = Thread.ofPlatform().start(f.ready.getFirst());
            assertTrue(queueEntered.await(5, TimeUnit.SECONDS));
            Thread awaiter = Thread.ofPlatform().start(() -> {
                try (var tick = mockStatic(TickThread.class)) {
                    tick.when(TickThread::isShutdownThread).thenReturn(true);
                    waiting.countDown();
                    f.pois.awaitPendingReleases(System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                } catch (Throwable error) { failure.set(error); }
                finally { finished.countDown(); }
            });
            try {
                assertTrue(waiting.await(5, TimeUnit.SECONDS));
                assertFalse(finished.await(100, TimeUnit.MILLISECONDS));
            } finally {
                queueAllowed.countDown();
                callback.join(5000); awaiter.join(5000);
            }
            assertFalse(callback.isAlive()); assertFalse(awaiter.isAlive());
            assertNull(failure.get());
            assertEquals(0, finished.getCount());
            f.shutdown();
            f.tick.when(() -> TickThread.isTickThreadFor(f.world, POS)).thenReturn(false);
            f.pois.finishPendingReleases();
            verify(f.pois, never()).release(POS);
            f.owner(true);
            f.pois.finishPendingReleases();
            verify(f.pois).release(POS);
        }
    }

    @Test
    void timeoutCancelsPendingAndLateCompletionCannotExecuteRelease() throws Exception {
        try (Fixture f = new Fixture()) {
            f.accept(false);
            f.shutdown();
            f.pois.awaitPendingReleases(System.nanoTime());
            verify(f.lease).close();
            assertTrue(f.pending.isEmpty());
            f.loaded();
            doCallRealMethod().when(f.tasks.getFirst()).queue();
            f.ready.getFirst().run();
            assertFalse(f.tasks.getFirst().execute());
            verify(f.pois, never()).release(POS);
            verify(f.lease).close();
        }
    }

    @Test
    void failedReadOrQueueReleasesLeaseWithoutTreatingFailureAsAnEmptyPoi() throws Exception {
        try (Fixture f = new Fixture()) {
            f.accept(true);
            when(f.lease.getIfReady()).thenThrow(new CompletionException(new IllegalStateException("read failed")));
            f.ready.getFirst().run();
            assertThrows(CompletionException.class, () -> f.tasks.getFirst().execute());
            verify(f.pois, never()).getType(POS);
            verifyNoInteractions(f.debug);
            verify(f.lease).close();
            assertTrue(f.pending.isEmpty());
        }
        try (Fixture f = new Fixture()) {
            f.accept(false);
            doThrow(new IllegalStateException("queue failed")).when(f.tasks.getFirst()).queue();
            f.ready.getFirst().run();
            verify(f.lease).close();
            assertTrue(f.pending.isEmpty());
            assertFalse(f.tasks.getFirst().execute());
        }
    }

    @Test
    void separateAcceptedReleasesAreNotCoalescedAndOutOfHeightDoesNotReadDisk() throws Exception {
        try (Fixture f = new Fixture()) {
            f.accept(false); f.accept(false);
            assertEquals(2, f.pending.size());
            f.loaded();
            f.ready.forEach(Runnable::run);
            f.tasks.forEach(PrioritisedExecutor.PrioritisedTask::execute);
            verify(f.pois, times(2)).release(POS);
            assertTrue(f.pending.isEmpty());
        }
        try (Fixture f = new Fixture()) {
            when(f.world.isOutsideBuildHeight(POS)).thenReturn(true);
            f.accept(false);
            verify(f.manager, never()).acquirePoiChunk(anyInt(), anyInt());
            verify(f.pois, never()).getType(POS);
        }
    }

    @Test
    void oneFailedShutdownReleaseDoesNotSkipOtherAcceptedReleases() throws Exception {
        try (Fixture f = new Fixture()) {
            f.accept(false); f.accept(false);
            when(f.lease.getIfReady()).thenThrow(new CompletionException(new IllegalStateException("read failed"))).thenReturn(f.chunk);
            f.ready.forEach(Runnable::run);
            f.shutdown();
            f.pois.awaitPendingReleases(System.nanoTime() + TimeUnit.SECONDS.toNanos(1));
            f.pois.finishPendingReleases();
            assertTrue(f.pending.isEmpty());
            verify(f.pois).release(POS);
            verify(f.lease, times(2)).close();
        }
    }
}

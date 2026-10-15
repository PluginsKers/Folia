package ca.spottedleaf.moonrise.patches.poi_lookup;

import ca.spottedleaf.concurrentutil.lock.ReentrantAreaLock;
import ca.spottedleaf.concurrentutil.map.concurrent.longs.ConcurrentChainedLong2ReferenceHashTable;
import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.patches.chunk_system.level.poi.PoiChunk;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkHolderManager;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.ChunkTaskScheduler;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.NewChunkHolder;
import ca.spottedleaf.moonrise.patches.chunk_system.scheduling.task.GenericDataLoadTask;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class PoiChunkLeaseTest {
    private static final class Fixture {
        final ChunkHolderManager manager = mock(ChunkHolderManager.class);
        final ChunkTaskScheduler scheduler = mock(ChunkTaskScheduler.class);
        final NewChunkHolder holder = mock(NewChunkHolder.class);
        final NewChunkHolder.GenericDataLoadTaskCallback task = mock(NewChunkHolder.GenericDataLoadTaskCallback.class);
        final ReentrantAreaLock tickets = new ReentrantAreaLock(4), scheduling = new ReentrantAreaLock(4);
        Consumer<GenericDataLoadTask.TaskResult<PoiChunk, Throwable>> completion;

        Fixture() throws Exception {
            set(manager, "taskScheduler", scheduler);
            set(manager, "ticketLockArea", tickets);
            set(scheduler, "schedulingLockArea", scheduling);
            var holders = ConcurrentChainedLong2ReferenceHashTable.<NewChunkHolder>createWithCapacity(16, 0.25f);
            holders.put(CoordinateUtils.getChunkKey(2, 3), holder);
            set(manager, "chunkHolders", holders);
            when(manager.acquirePoiChunk(2, 3)).thenCallRealMethod();
            when(holder.getOrLoadPoiData(any())).thenAnswer(call -> {
                assertTrue(tickets.isHeldByCurrentThread(2, 3));
                assertTrue(scheduling.isHeldByCurrentThread(2, 3));
                completion = call.getArgument(0);
                return task;
            });
            doAnswer(call -> {
                assertFalse(tickets.isHeldByCurrentThread(2, 3));
                assertFalse(scheduling.isHeldByCurrentThread(2, 3));
                return null;
            }).when(task).schedule();
        }

        private static void set(Object object, String name, Object value) throws Exception {
            var field = (object instanceof ChunkHolderManager ? ChunkHolderManager.class : ChunkTaskScheduler.class).getDeclaredField(name);
            field.setAccessible(true);
            field.set(object, value);
        }

        void checkSameTicketReleasedOnce() {
            ArgumentCaptor<Long> added = ArgumentCaptor.forClass(Long.class), removed = ArgumentCaptor.forClass(Long.class);
            verify(manager).addTicketAtLevel(eq(ChunkTaskScheduler.POI_LOAD), eq(2), eq(3), eq(ChunkHolderManager.MAX_TICKET_LEVEL), added.capture());
            verify(manager).removeTicketAtLevel(eq(ChunkTaskScheduler.POI_LOAD), eq(2), eq(3), eq(ChunkHolderManager.MAX_TICKET_LEVEL), removed.capture());
            assertEquals(added.getValue(), removed.getValue());
        }
    }

    @Test
    void leaseWaitsWithoutLoadingIntoWorldAndReleasesExactlyItsTicket() throws Exception {
        Fixture f = new Fixture();
        var lease = f.manager.acquirePoiChunk(2, 3);
        assertNull(lease.getIfReady());
        PoiChunk loaded = mock(PoiChunk.class);
        f.completion.accept(new GenericDataLoadTask.TaskResult<>(loaded, null));
        assertSame(loaded, lease.getIfReady());
        verify(loaded, never()).load();
        lease.close(); lease.close();
        verify(f.task).cancel();
        f.checkSameTicketReleasedOnce();
    }

    @Test
    void closedLeaseRejectsLateCompletionAndAlreadyLoadedLeaseStillRetainsTicket() throws Exception {
        Fixture f = new Fixture();
        var lease = f.manager.acquirePoiChunk(2, 3);
        lease.close();
        f.completion.accept(new GenericDataLoadTask.TaskResult<>(mock(PoiChunk.class), null));
        assertThrows(IllegalStateException.class, lease::getIfReady);
        f.checkSameTicketReleasedOnce();
        Fixture loaded = new Fixture();
        PoiChunk chunk = mock(PoiChunk.class);
        when(loaded.holder.getPoiChunk()).thenReturn(chunk);
        var ready = loaded.manager.acquirePoiChunk(2, 3);
        assertSame(chunk, ready.getIfReady());
        verify(loaded.holder, never()).getOrLoadPoiData(any());
        ready.close();
        loaded.checkSameTicketReleasedOnce();
    }

    @Test
    void schedulingFailureAndCancellationFailureReleaseTicket() throws Exception {
        Fixture f = new Fixture();
        doThrow(new IllegalStateException("schedule rejected")).when(f.task).schedule();
        assertThrows(IllegalStateException.class, () -> f.manager.acquirePoiChunk(2, 3));
        f.checkSameTicketReleasedOnce();
        Fixture cancel = new Fixture();
        var lease = cancel.manager.acquirePoiChunk(2, 3);
        doThrow(new IllegalStateException("cancel failed")).when(cancel.task).cancel();
        assertThrows(IllegalStateException.class, lease::close);
        lease.close();
        cancel.checkSameTicketReleasedOnce();
    }

    @Test
    void failedCompletionIsNotReportedAsAnEmptyLoadedSection() throws Exception {
        Fixture f = new Fixture();
        var lease = f.manager.acquirePoiChunk(2, 3);
        f.completion.accept(new GenericDataLoadTask.TaskResult<>(null, new IllegalStateException("load failure")));
        assertThrows(CompletionException.class, lease::getIfReady);
        lease.close();
        f.checkSameTicketReleasedOnce();
    }

    @Test
    void readinessNotifiesSuccessAndFailureWithoutLoadingWorldData() throws Exception {
        for (boolean failed : new boolean[]{false, true}) {
            Fixture f = new Fixture();
            var lease = f.manager.acquirePoiChunk(2, 3);
            AtomicInteger calls = new AtomicInteger();
            lease.whenReady(calls::incrementAndGet);
            assertFalse(lease.isReady());
            assertEquals(0, calls.get());
            PoiChunk chunk = mock(PoiChunk.class);
            f.completion.accept(failed
                ? new GenericDataLoadTask.TaskResult<>(null, new IllegalStateException("read failed"))
                : new GenericDataLoadTask.TaskResult<>(chunk, null));
            assertTrue(lease.isReady());
            assertEquals(1, calls.get());
            lease.whenReady(calls::incrementAndGet);
            assertEquals(2, calls.get());
            verify(chunk, never()).load();
            lease.close();
            f.checkSameTicketReleasedOnce();
        }
    }
}

package io.papermc.paper.threadedregions;

import ca.spottedleaf.concurrentutil.executor.PrioritisedExecutor;
import ca.spottedleaf.concurrentutil.util.Priority;
import ca.spottedleaf.moonrise.common.util.TickThread;
import ca.spottedleaf.moonrise.patches.chunk_system.level.entity.EntityLookup;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.bukkit.craftbukkit.entity.CraftEntity;
import org.bukkit.craftbukkit.entity.CraftVillager;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class ShutdownEntityRemovalTest {
    private static final class Fixture {
        final ServerLevel world = mock(ServerLevel.class);
        final Entity entity = mock(Entity.class);
        final RegionizedWorldData data = mock(RegionizedWorldData.class);
        final TickRegions.TickRegionData tickData = mock(TickRegions.TickRegionData.class);
        final ThreadedRegionizer<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> regioniser;
        final ThreadedRegionizer.ThreadedRegion<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> region;

        @SuppressWarnings("unchecked")
        Fixture() throws Exception {
            regioniser = mock(ThreadedRegionizer.class);
            region = mock(ThreadedRegionizer.ThreadedRegion.class);
            field(ServerLevel.class, "regioniser", world, regioniser);
            when(entity.level()).thenReturn(world);
            when(entity.moonrise$getSectionX()).thenReturn(7);
            when(entity.moonrise$getSectionZ()).thenReturn(-9);
            when(regioniser.getRegionAtSynchronised(7, -9)).thenReturn(region);
            when(region.getData()).thenReturn(tickData);
            when(region.tryMarkTicking(any())).thenReturn(true);
            when(tickData.getRegionizedData(world.worldRegionData)).thenReturn(data);
            when(data.hasEntity(entity)).thenReturn(true);
        }
    }

    private static void field(Class<?> type, String name, Object owner, Object value) throws Exception {
        var field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }

    private static RegionShutdownThread shutdown(boolean halted) throws Exception {
        RegionShutdownThread thread = new RegionShutdownThread("shutdown removal test");
        field(RegionShutdownThread.class, "schedulerHalted", thread, halted);
        return thread;
    }

    @Test
    void usesRegisteredSectionAndExistingWorldData() throws Exception {
        Fixture f = new Fixture();
        RegionShutdownThread thread = shutdown(true);
        thread.runEntityRemoval(f.entity, () -> assertSame(f.region, thread.shuttingDown));
        assertNull(thread.shuttingDown);
        verify(f.entity, never()).chunkPosition();
        verify(f.tickData, never()).getOrCreateRegionizedData(any());
        var order = inOrder(f.region, f.data);
        order.verify(f.region).tryMarkTicking(any());
        order.verify(f.region).getData();
        order.verify(f.data).hasEntity(f.entity);
        order.verify(f.region).markNotTicking();
    }

    @Test
    void competingRegionRejectsOnceBeforeMembershipOrRemoval() throws Exception {
        Fixture f = new Fixture();
        when(f.region.tryMarkTicking(any())).thenReturn(false);
        RegionShutdownThread thread = shutdown(true);
        Runnable removal = mock(Runnable.class);
        assertThrows(IllegalStateException.class, () -> thread.runEntityRemoval(f.entity, removal));
        verify(f.region).tryMarkTicking(any());
        verify(f.region, never()).getData();
        verify(f.region, never()).markNotTicking();
        verifyNoInteractions(removal, f.data);
        assertNull(thread.shuttingDown);
    }

    @Test
    void nestedReturnToOuterRegionBorrowsItOnlyOnceAndReleasesWithoutContext() throws Exception {
        Fixture first = new Fixture(), second = new Fixture();
        RegionShutdownThread thread = shutdown(true);
        List<String> releases = new ArrayList<>();
        doAnswer(call -> { assertNull(thread.shuttingDown); releases.add("first"); return true; }).when(first.region).markNotTicking();
        doAnswer(call -> { assertNull(thread.shuttingDown); releases.add("second"); return true; }).when(second.region).markNotTicking();
        RuntimeException failure = new RuntimeException("nested removal");
        assertSame(failure, assertThrows(RuntimeException.class, () -> thread.runEntityRemoval(first.entity, () -> {
            thread.runEntityRemoval(second.entity, () -> thread.runEntityRemoval(first.entity, () -> {
                assertSame(first.region, thread.shuttingDown);
                throw failure;
            }));
        })));
        assertNull(thread.shuttingDown);
        assertEquals(List.of("second", "first"), releases);
        verify(first.region).tryMarkTicking(any());
        verify(second.region).tryMarkTicking(any());
        verify(first.region).markNotTicking();
        verify(second.region).markNotTicking();
        // No borrowed state survives the failed outer operation.
        thread.runEntityRemoval(first.entity, () -> assertSame(first.region, thread.shuttingDown));
        verify(first.region, times(2)).tryMarkTicking(any());
        verify(first.region, times(2)).markNotTicking();
    }

    @Test
    void releaseFailureStillRestoresPreviousContextAndClearsBorrowedState() throws Exception {
        Fixture outer = new Fixture(), f = new Fixture();
        RegionShutdownThread thread = shutdown(true);
        thread.shuttingDown = outer.region;
        doThrow(new IllegalStateException("release failed")).when(f.region).markNotTicking();
        assertThrows(IllegalStateException.class, () -> thread.runEntityRemoval(f.entity, () -> {}));
        assertSame(outer.region, thread.shuttingDown);
        doReturn(true).when(f.region).markNotTicking();
        thread.runEntityRemoval(f.entity, () -> {});
        verify(f.region, times(2)).tryMarkTicking(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void realRegionMigrationIsDeferredUntilScopeRelease() throws Exception {
        ServerLevel world = mock(ServerLevel.class);
        RegionShutdownThread thread = shutdown(true);
        ThreadedRegionizer.RegionCallbacks<TickRegions.TickRegionData, TickRegions.TickRegionSectionData> callbacks =
            mock(ThreadedRegionizer.RegionCallbacks.class);
        when(callbacks.createNewData(any())).thenAnswer(call -> mock(TickRegions.TickRegionData.class));
        doAnswer(call -> { assertNull(thread.shuttingDown); return null; }).when(callbacks).onRegionDestroy(any());
        var regioniser = new ThreadedRegionizer<>(2, 0.5, 1, 1, 0, world, callbacks);
        field(ServerLevel.class, "regioniser", world, regioniser);
        regioniser.addChunk(0, 0);
        var original = regioniser.getRegionAtSynchronised(0, 0);
        thread.runTickTask(world, 0, 0, () -> {
            assertSame(original, thread.shuttingDown);
            assertFalse(original.tryMarkTicking(() -> false));
            regioniser.addChunk(3, 0);
            assertSame(original, regioniser.getRegionAtSynchronised(0, 0));
            assertNotSame(original, regioniser.getRegionAtSynchronised(3, 0));
            verify(callbacks, never()).onRegionDestroy(any());
        });
        assertNull(thread.shuttingDown);
        var merged = regioniser.getRegionAtSynchronised(0, 0);
        assertSame(merged, regioniser.getRegionAtSynchronised(3, 0));
        assertTrue(merged.tryMarkTicking(() -> false));
        merged.markNotTicking();
        verify(callbacks).onRegionDestroy(any());
    }

    @Test
    void nestedCrossWorldRemovalRestoresOuterContextAfterFailure() throws Exception {
        Fixture first = new Fixture(), second = new Fixture();
        RegionShutdownThread thread = shutdown(true);
        RuntimeException failure = new RuntimeException("nested removal");
        thread.runEntityRemoval(first.entity, () -> {
            assertSame(first.region, thread.shuttingDown);
            assertSame(failure, assertThrows(RuntimeException.class, () -> thread.runEntityRemoval(second.entity, () -> {
                assertSame(second.region, thread.shuttingDown);
                throw failure;
            })));
            assertSame(first.region, thread.shuttingDown);
        });
        assertNull(thread.shuttingDown);
    }

    @Test
    void missingRegisteredRegionRejectsBeforeRemoval() throws Exception {
        Fixture f = new Fixture();
        when(f.regioniser.getRegionAtSynchronised(7, -9)).thenReturn(null);
        Runnable removal = mock(Runnable.class);
        assertThrows(IllegalStateException.class, () -> shutdown(true).runEntityRemoval(f.entity, removal));
        verifyNoInteractions(removal);
    }

    @Test
    void regionWithoutEntityRejectsBeforeRemoval() throws Exception {
        Fixture f = new Fixture();
        when(f.data.hasEntity(f.entity)).thenReturn(false);
        Runnable removal = mock(Runnable.class);
        assertThrows(IllegalStateException.class, () -> shutdown(true).runEntityRemoval(f.entity, removal));
        verifyNoInteractions(removal);
        verify(f.region).markNotTicking();
    }

    @Test
    void missingWorldDataDoesNotCreateAnEmptyReplacement() throws Exception {
        Fixture f = new Fixture();
        when(f.tickData.getRegionizedData(f.world.worldRegionData)).thenReturn(null);
        Runnable removal = mock(Runnable.class);
        assertThrows(IllegalStateException.class, () -> shutdown(true).runEntityRemoval(f.entity, removal));
        verifyNoInteractions(removal);
        verify(f.tickData, never()).getOrCreateRegionizedData(any());
        verify(f.region).markNotTicking();
    }

    @Test
    void failedHaltRejectsEvenUnregisteredEntitiesBeforeAnyAction() throws Exception {
        Fixture f = new Fixture();
        when(f.entity.hasInactiveLevelCallback()).thenReturn(true);
        Runnable removal = mock(Runnable.class);
        assertThrows(IllegalStateException.class, () -> shutdown(false).runEntityRemoval(f.entity, removal));
        verifyNoInteractions(removal);
        verify(f.entity, never()).hasInactiveLevelCallback();
    }

    @Test
    void incompleteRemovalIsNotMistakenForAnInactiveCallback() throws Exception {
        Fixture f = new Fixture();
        when(f.entity.isRemoved()).thenReturn(true);
        when(f.entity.moonrise$getSectionX()).thenReturn(Integer.MIN_VALUE);
        when(f.entity.moonrise$getSectionZ()).thenReturn(Integer.MIN_VALUE);
        Runnable removal = mock(Runnable.class);
        assertThrows(IllegalStateException.class, () -> shutdown(true).runEntityRemoval(f.entity, removal));
        verifyNoInteractions(removal);
        verifyNoInteractions(f.regioniser);
    }

    @Test
    void unregisteredAndFullyRemovedCallbacksRunWithoutBorrowingOuterRegion() throws Exception {
        Fixture outer = new Fixture();
        RegionShutdownThread thread = shutdown(true);
        for (EntityInLevelCallback callback : List.of(EntityInLevelCallback.NULL, EntityLookup.NoOpCallback.INSTANCE)) {
            Entity detached = mock(Entity.class, CALLS_REAL_METHODS);
            field(Entity.class, "levelCallback", detached, callback);
            assertTrue(detached.hasInactiveLevelCallback());
            thread.shuttingDown = outer.region;
            thread.runEntityRemoval(detached, () -> assertNull(thread.shuttingDown));
            assertSame(outer.region, thread.shuttingDown);
        }
        Entity partial = mock(Entity.class, CALLS_REAL_METHODS);
        field(Entity.class, "levelCallback", partial, mock(EntityInLevelCallback.class));
        assertFalse(partial.hasInactiveLevelCallback());
    }

    @Test
    void scopedTickTaskUsesTargetWorldAndRestoresEntityRegion() throws Exception {
        Fixture owner = new Fixture(), destination = new Fixture();
        RegionShutdownThread thread = shutdown(true);
        thread.shuttingDown = owner.region;
        thread.runTickTask(destination.world, 7, -9, () -> assertSame(destination.region, thread.shuttingDown));
        assertSame(owner.region, thread.shuttingDown);
    }

    @Test
    void missingTaskRegionPreservesInlineNullContextWithoutCreatingRegion() throws Exception {
        Fixture f = new Fixture();
        RegionShutdownThread thread = shutdown(true);
        thread.shuttingDown = f.region;
        RuntimeException failure = new RuntimeException("task failed");
        assertSame(failure, assertThrows(RuntimeException.class, () -> thread.runTickTask(f.world, 100, 100, () -> {
            assertNull(thread.shuttingDown);
            throw failure;
        })));
        assertSame(f.region, thread.shuttingDown);
        verify(f.regioniser).getRegionAtSynchronised(100, 100);
        verifyNoMoreInteractions(f.regioniser);
    }

    @Test
    void unconfirmedHaltDoesNotRunOrResolveTickTask() throws Exception {
        Fixture f = new Fixture();
        Runnable task = mock(Runnable.class);
        assertThrows(IllegalStateException.class, () -> shutdown(false).runTickTask(f.world, 7, -9, task));
        verifyNoInteractions(f.regioniser, task);
    }

    @Test
    void ordinaryRouterStillRunsOwnedTasksAndQueuesForeignTasks() {
        RegionizedTaskQueue queue = spy(new RegionizedTaskQueue());
        ServerLevel world = mock(ServerLevel.class);
        Runnable task = mock(Runnable.class);
        var queued = mock(PrioritisedExecutor.PrioritisedTask.class);
        doReturn(queued).when(queue).queueTickTaskQueue(world, 1, 2, task, Priority.NORMAL);
        try (var ticks = mockStatic(TickThread.class)) {
            ticks.when(() -> TickThread.isTickThreadFor(world, 1, 2)).thenReturn(true);
            assertNull(queue.queueOrExecuteTickTask(world, 1, 2, task));
            verify(task).run();
            ticks.when(() -> TickThread.isTickThreadFor(world, 1, 2)).thenReturn(false);
            assertSame(queued, queue.queueOrExecuteTickTask(world, 1, 2, task));
            verifyNoMoreInteractions(task);
        }
    }

    @Test
    void ownerRejectionDoesNotChangePluginRemovedFlag() throws Exception {
        Entity entity = mock(Entity.class);
        CraftEntity craft = mock(CraftEntity.class, CALLS_REAL_METHODS);
        field(CraftEntity.class, "entity", craft, entity);
        doThrow(new IllegalStateException("foreign owner")).when(craft).getHandle();
        assertThrows(IllegalStateException.class, craft::remove);
        assertFalse(entity.pluginRemoved);
        verify(entity, never()).discard(any());
    }

    @Test
    void villagerPoiCleanupStaysInsideSharedRemovalDispatchAndPrecedesDiscard() throws Exception {
        Villager entity = mock(Villager.class);
        CraftVillager craft = mock(CraftVillager.class, CALLS_REAL_METHODS);
        field(CraftEntity.class, "entity", craft, entity);
        doReturn(entity).when(craft).getHandle();
        List<String> calls = new ArrayList<>();
        doAnswer(call -> { calls.add("poi"); return null; }).when(entity).releaseAllPois();
        doAnswer(call -> { calls.add("discard"); return null; }).when(entity).discard(any());
        try (var shutdown = mockStatic(RegionShutdownThread.class)) {
            shutdown.when(() -> RegionShutdownThread.tryRunEntityRemoval(eq(entity), any())).thenAnswer(call -> {
                calls.add("scope");
                ((Runnable) call.getArgument(1)).run();
                calls.add("restore");
                return true;
            });
            craft.remove();
        }
        assertEquals(List.of("scope", "poi", "discard", "restore"), calls);
        assertTrue(entity.pluginRemoved);
    }
}

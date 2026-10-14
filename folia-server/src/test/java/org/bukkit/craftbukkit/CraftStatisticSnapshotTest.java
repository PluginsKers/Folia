package org.bukkit.craftbukkit;

import com.mojang.authlib.GameProfile;
import io.papermc.paper.threadedregions.EntityScheduler;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.dedicated.DedicatedPlayerList;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.world.entity.Entity;
import org.bukkit.Statistic;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class CraftStatisticSnapshotTest {
    private final GameProfile profile = new GameProfile(UUID.randomUUID(), "snapshot");
    private final Set<Statistic> requested = Set.of(Statistic.PLAY_ONE_MINUTE, Statistic.WALK_ONE_CM, Statistic.MOB_KILLS);

    @Test
    void onlineCaptureWaitsForOneOwnerCallbackAndNeverReadsDisk() throws Exception {
        CraftServer server = mock(CraftServer.class);
        DedicatedPlayerList players = mock(DedicatedPlayerList.class);
        when(server.getHandle()).thenReturn(players);
        ServerPlayer online = mock(ServerPlayer.class);
        EntityScheduler scheduler = scheduler(online);
        when(players.getPlayer(profile.id())).thenReturn(online);
        AtomicReference<Consumer<Entity>> owner = new AtomicReference<>();
        when(scheduler.schedule(any(), any(), eq(1L))).thenAnswer(call -> {
            owner.set(call.getArgument(0));
            return true;
        });
        ServerStatsCounter counter = counter();
        when(online.getStats()).thenReturn(counter);
        var future = CraftStatistic.getStatisticsAsync(server, profile, requested);
        assertFalse(future.isDone());
        verify(online, never()).getStats();
        owner.get().accept(online);
        assertEquals(Map.of(Statistic.PLAY_ONE_MINUTE, 1800, Statistic.WALK_ONE_CM, 90, Statistic.MOB_KILLS, 0), future.join());
        assertThrows(UnsupportedOperationException.class, () -> future.join().put(Statistic.JUMP, 2));
        verify(players, never()).getPlayerStatsAsync(any(), anyBoolean());
        verify(scheduler).schedule(any(), any(), eq(1L));
    }

    @Test
    void offlineSelectionNeverReentersAnOnlineFacade() {
        CraftServer server = mock(CraftServer.class);
        DedicatedPlayerList players = mock(DedicatedPlayerList.class);
        when(server.getHandle()).thenReturn(players);
        when(players.getPlayer(profile.id())).thenReturn(null, mock(ServerPlayer.class));
        CompletableFuture<ServerStatsCounter> disk = new CompletableFuture<>();
        when(players.getPlayerStatsAsync(profile, false)).thenReturn(disk);
        var future = CraftStatistic.getStatisticsAsync(server, profile, requested);
        disk.complete(counter());
        assertEquals(1800, future.join().get(Statistic.PLAY_ONE_MINUTE));
        verify(players).getPlayer(profile.id());
        verify(players).getPlayerStatsAsync(profile, false);
    }

    @Test
    void retirementFallsBackToOrderedDiskAndPropagatesSaveBarrier() throws Exception {
        CraftServer server = mock(CraftServer.class);
        DedicatedPlayerList players = mock(DedicatedPlayerList.class);
        when(server.getHandle()).thenReturn(players);
        ServerPlayer online = mock(ServerPlayer.class);
        EntityScheduler scheduler = scheduler(online);
        when(players.getPlayer(profile.id())).thenReturn(online);
        AtomicReference<Consumer<Entity>> retired = new AtomicReference<>();
        when(scheduler.schedule(any(), any(), eq(1L))).thenAnswer(call -> {
            retired.set(call.getArgument(1));
            return true;
        });
        CompletableFuture<ServerStatsCounter> disk = new CompletableFuture<>();
        when(players.getPlayerStatsAsync(profile, false)).thenReturn(disk);
        var future = CraftStatistic.getStatisticsAsync(server, profile, requested);
        retired.get().accept(online);
        assertFalse(future.isDone());
        IOException failure = new IOException("previous final save failed");
        disk.completeExceptionally(failure);
        assertSame(failure, assertThrows(CompletionException.class, future::join).getCause());
        verify(online, never()).getStats();
        verify(players).getPlayerStatsAsync(profile, false);
    }

    @Test
    void alreadyRetiredSchedulerReadsDiskOnce() throws Exception {
        CraftServer server = mock(CraftServer.class);
        DedicatedPlayerList players = mock(DedicatedPlayerList.class);
        when(server.getHandle()).thenReturn(players);
        ServerPlayer online = mock(ServerPlayer.class);
        scheduler(online);
        when(players.getPlayer(profile.id())).thenReturn(online);
        ServerStatsCounter counter = counter();
        when(players.getPlayerStatsAsync(profile, false)).thenReturn(CompletableFuture.completedFuture(counter));
        assertEquals(90, CraftStatistic.getStatisticsAsync(server, profile, requested).join().get(Statistic.WALK_ONE_CM));
        verify(players).getPlayerStatsAsync(profile, false);
        verify(online, never()).getStats();
    }

    @Test
    void typedStatisticsAreRejectedBeforeSchedulingOrReading() {
        CraftServer server = mock(CraftServer.class);
        assertThrows(IllegalArgumentException.class, () -> CraftStatistic.getStatisticsAsync(server, profile, Set.of(Statistic.MINE_BLOCK)));
        assertEquals(Map.of(), CraftStatistic.getStatisticsAsync(server, profile, Set.of()).join());
        verifyNoInteractions(server);
    }

    private static ServerStatsCounter counter() {
        ServerStatsCounter counter = mock(ServerStatsCounter.class);
        when(counter.getValue(CraftStatistic.getNMSStatistic(Statistic.PLAY_ONE_MINUTE))).thenReturn(1800);
        when(counter.getValue(CraftStatistic.getNMSStatistic(Statistic.WALK_ONE_CM))).thenReturn(90);
        return counter;
    }

    private static EntityScheduler scheduler(ServerPlayer online) throws Exception {
        CraftPlayer craft = mock(CraftPlayer.class);
        when(online.getBukkitEntity()).thenReturn(craft);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        var field = org.bukkit.craftbukkit.entity.CraftEntity.class.getField("taskScheduler");
        field.setAccessible(true);
        field.set(craft, scheduler);
        return scheduler;
    }
}

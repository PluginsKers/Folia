package ca.spottedleaf.moonrise.patches.poi_lookup;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import ca.spottedleaf.moonrise.common.util.TickThread;
import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.threadedregions.RegionizedWorldData;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.StructureTags;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.animal.feline.Cat;
import net.minecraft.world.entity.npc.CatSpawner;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.phys.AABB;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class CatSpawnerTest {
    private static final class Fixture {
        final ServerLevel level = mock(ServerLevel.class, RETURNS_DEEP_STUBS);
        final RegionizedWorldData data = mock(RegionizedWorldData.class, CALLS_REAL_METHODS);
        final GameRules rules = mock(GameRules.class);
        final WorldConfiguration config = mock(WorldConfiguration.class);
        final CatSpawner spawner = new CatSpawner();
        final Map<Long, PoiSection> sections = new HashMap<>();
        Long pendingSection;
        boolean ready;

        Fixture() throws Exception {
            var world = RegionizedWorldData.class.getField("world");
            world.setAccessible(true);
            world.set(data, level);
            data.catSpawnerNextTick = 1200;
            when(level.getCurrentWorldData()).thenReturn(data);
            when(level.getGameRules()).thenReturn(rules);
            when(rules.get(GameRules.SPAWN_MOBS)).thenReturn(true);
            when(level.getLocalPlayers()).thenReturn(List.of(mock(ServerPlayer.class)));
            when(level.hasChunksAt(-10, -10, 10, 10)).thenReturn(true);
            when(level.isCloseToVillage(BlockPos.ZERO, 2)).thenReturn(true);
            when(level.enabledFeatures()).thenReturn(FeatureFlags.VANILLA_SET);
            when(level.getEntitiesOfClass(eq(Cat.class), any(AABB.class))).thenReturn(List.of());
            when(level.tickRateManager().runsNormally()).thenReturn(true);
            config.unsupportedSettings = mock(WorldConfiguration.UnsupportedSettings.class);
            config.unsupportedSettings.ticking = mock(WorldConfiguration.UnsupportedSettings.Ticking.class);
            config.unsupportedSettings.ticking.chunks = true;
            when(level.paperConfig()).thenReturn(config);
            PoiManager manager = mock(PoiManager.class);
            var height = SectionStorage.class.getDeclaredField("levelHeightAccessor");
            height.setAccessible(true);
            height.set(manager, LevelHeightAccessor.create(-64, 128));
            when(level.getPoiManager()).thenReturn(manager);
        }

        void bed(BlockPos pos) {
            long key = CoordinateUtils.getChunkSectionKey(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4);
            var home = BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.HOME);
            PoiSection section = sections.computeIfAbsent(key, ignored -> new PoiSection(() -> {}));
            section.byType.computeIfAbsent(home, ignored -> new java.util.HashSet<>())
                .add(new PoiRecord.Packed(pos, home, 0).unpack(() -> {}));
        }

        void fiveBeds() { for (int x = -5; x < 0; ++x) bed(new BlockPos(x, 0, -1)); }

        boolean advance(PoiAccess.Search search) {
            return search.advance(key -> !ready && pendingSection != null && pendingSection.longValue() == key
                ? null : Optional.ofNullable(sections.get(key)));
        }

        void start() {
            data.catSpawnAttempt = new CatSpawner.SpawnAttempt(level, BlockPos.ZERO);
            spawner.tick(level, true);
        }
    }

    @Test
    void fullColdTraversalResumesOnceAndPreservesFiveBedFiveCatThresholds() throws Exception {
        try (var tick = mockStatic(TickThread.class); var placement = mockStatic(SpawnPlacements.class)) {
            tick.when(() -> TickThread.isTickThreadFor(any(), any(AABB.class))).thenReturn(true);
            placement.when(() -> SpawnPlacements.isSpawnPositionOk(eq(EntityTypes.CAT), any(), any())).thenReturn(true);
            for (int beds = 4; beds <= 5; ++beds) {
                for (int cats = 4; cats <= 5; ++cats) {
                    Fixture f = new Fixture();
                    for (int x = -beds; x < 0; ++x) f.bed(new BlockPos(x, 0, -1));
                    when(f.level.getEntitiesOfClass(eq(Cat.class), any(AABB.class)))
                        .thenReturn(java.util.Collections.nCopies(cats, mock(Cat.class)));
                    f.pendingSection = CoordinateUtils.getChunkSectionKey(1, 0, 1);
                    try (var sessions = mockConstruction(PoiSearchSession.class, (mock, context) ->
                            when(mock.advance(any())).thenAnswer(call -> f.advance(call.getArgument(0))));
                         var created = mockConstruction(Cat.class)) {
                        f.start();
                        assertNotNull(f.data.catSpawnAttempt);
                        assertTrue(created.constructed().isEmpty());
                        for (int i = 0; i < 1201; ++i) f.spawner.tick(f.level, true);
                        assertEquals(1200, f.data.catSpawnerNextTick);
                        assertEquals(1, sessions.constructed().size());
                        f.ready = true;
                        f.spawner.tick(f.level, true);
                        assertNull(f.data.catSpawnAttempt);
                        verify(sessions.constructed().getFirst()).close();
                        assertEquals(beds == 5 && cats == 4 ? 1 : 0, created.constructed().size());
                        if (!created.constructed().isEmpty()) {
                            Cat cat = created.constructed().getFirst();
                            verify(cat).finalizeSpawn(eq(f.level), any(), eq(EntitySpawnReason.NATURAL), isNull());
                            verify(cat, never()).setPersistenceRequired();
                            verify(f.level).addFreshEntityWithPassengers(cat);
                        }
                    }
                }
            }
        }
    }

    @Test
    void resumedAttemptRecountsPreviouslyVisitedBedsAndCurrentCats() throws Exception {
        try (var tick = mockStatic(TickThread.class); var placement = mockStatic(SpawnPlacements.class)) {
            tick.when(() -> TickThread.isTickThreadFor(any(), any(AABB.class))).thenReturn(true);
            placement.when(() -> SpawnPlacements.isSpawnPositionOk(eq(EntityTypes.CAT), any(), any())).thenReturn(true);
            for (int change = 0; change < 3; ++change) {
                Fixture f = new Fixture();
                f.fiveBeds();
                f.pendingSection = CoordinateUtils.getChunkSectionKey(1, 0, 1);
                try (var sessions = mockConstruction(PoiSearchSession.class, (mock, context) ->
                        when(mock.advance(any())).thenAnswer(call -> f.advance(call.getArgument(0))));
                     var created = mockConstruction(Cat.class)) {
                    f.start();
                    assertNotNull(f.data.catSpawnAttempt);
                    var records = f.sections.values().iterator().next().byType.values().iterator().next();
                    if (change == 0) records.remove(records.iterator().next());
                    if (change == 1) {
                        PoiRecord occupied = records.iterator().next();
                        records.remove(occupied);
                        records.add(new PoiRecord(occupied.getPos(), occupied.getPoiType(), () -> {}));
                    }
                    if (change == 2) when(f.level.getEntitiesOfClass(eq(Cat.class), any(AABB.class)))
                        .thenReturn(java.util.Collections.nCopies(5, mock(Cat.class)));
                    f.ready = true;
                    f.spawner.tick(f.level, true);
                    assertNull(f.data.catSpawnAttempt);
                    assertTrue(created.constructed().isEmpty());
                    verify(sessions.constructed().getFirst()).close();
                }
            }
        }
    }

    @Test
    void sphereBoundaryCountsOccupiedHomeOnly() throws Exception {
        try (var tick = mockStatic(TickThread.class); var placement = mockStatic(SpawnPlacements.class)) {
            tick.when(() -> TickThread.isTickThreadFor(any(), any(AABB.class))).thenReturn(true);
            placement.when(() -> SpawnPlacements.isSpawnPositionOk(eq(EntityTypes.CAT), any(), any())).thenReturn(true);
            for (BlockPos fifth : List.of(new BlockPos(48, 0, 0), new BlockPos(48, 0, 1))) {
                Fixture f = new Fixture();
                for (int x = -4; x < 0; ++x) f.bed(new BlockPos(x, 0, -1));
                f.bed(fifth);
                try (var sessions = mockConstruction(PoiSearchSession.class, (mock, context) ->
                        when(mock.advance(any())).thenAnswer(call -> f.advance(call.getArgument(0))));
                     var created = mockConstruction(Cat.class)) {
                    f.start();
                    assertEquals(fifth.getZ() == 0 ? 1 : 0, created.constructed().size());
                    assertNull(f.data.catSpawnAttempt);
                }
            }
        }
    }

    @Test
    void disablingAnyCustomSpawnerGateClosesPendingEvenWithoutSpawnerTick() throws Exception {
        for (int disabled = 0; disabled < 5; ++disabled) {
            Fixture f = new Fixture();
            var attempt = mock(CatSpawner.SpawnAttempt.class);
            f.data.catSpawnAttempt = attempt;
            switch (disabled) {
                case 0 -> when(f.level.tickRateManager().runsNormally()).thenReturn(false);
                case 1 -> f.config.unsupportedSettings.ticking.chunks = false;
                case 2 -> when(f.level.isDebug()).thenReturn(true);
                case 3 -> when(f.rules.get(GameRules.SPAWN_MOBS)).thenReturn(false);
                case 4 -> when(f.level.getLocalPlayers()).thenReturn(List.of());
            }
            CatSpawner.cancelIfDisabled(f.level, f.data);
            assertNull(f.data.catSpawnAttempt);
            verify(attempt).close();
        }
    }

    @Test
    void ownershipIncludesEntityLookupMarginAndInvalidPlacementNeverQueriesEntities() throws Exception {
        try (var tick = mockStatic(TickThread.class); var placement = mockStatic(SpawnPlacements.class)) {
            for (int invalid = 0; invalid < 3; ++invalid) {
                Fixture f = new Fixture();
                tick.when(() -> TickThread.isTickThreadFor(eq(f.level), eq(new AABB(BlockPos.ZERO).inflate(50.0, 8.0, 50.0))))
                    .thenReturn(invalid != 0);
                placement.when(() -> SpawnPlacements.isSpawnPositionOk(EntityTypes.CAT, f.level, BlockPos.ZERO)).thenReturn(invalid != 1);
                if (invalid == 2) when(f.level.hasChunksAt(-10, -10, 10, 10)).thenReturn(false);
                try (var sessions = mockConstruction(PoiSearchSession.class)) {
                    f.start();
                    assertNull(f.data.catSpawnAttempt);
                    verify(sessions.constructed().getFirst(), never()).advance(any());
                    verify(sessions.constructed().getFirst()).close();
                    verify(f.level, never()).getEntitiesOfClass(eq(Cat.class), any(AABB.class));
                }
            }
        }
    }

    @Test
    void hutKeepsSixteenBlockCatLimitPersistentNaturalSpawnAndOwnerMargin() throws Exception {
        try (var tick = mockStatic(TickThread.class); var placement = mockStatic(SpawnPlacements.class)) {
            for (int condition = 0; condition < 3; ++condition) {
                Fixture f = new Fixture();
                f.data.catSpawnerNextTick = 0;
                ServerPlayer player = mock(ServerPlayer.class);
                when(player.blockPosition()).thenReturn(new BlockPos(-8, 0, -8));
                when(f.level.getRandomLocalPlayer()).thenReturn(player);
                when(f.level.getRandom().nextInt(24)).thenReturn(0);
                when(f.level.getRandom().nextBoolean()).thenReturn(false);
                when(f.level.isCloseToVillage(BlockPos.ZERO, 2)).thenReturn(false);
                when(f.level.structureManager().getStructureWithPieceAt(BlockPos.ZERO, StructureTags.CATS_SPAWN_IN).isValid()).thenReturn(true);
                tick.when(() -> TickThread.isTickThreadFor(eq(f.level), eq(new AABB(BlockPos.ZERO).inflate(18.0, 8.0, 18.0))))
                    .thenReturn(condition != 2);
                placement.when(() -> SpawnPlacements.isSpawnPositionOk(EntityTypes.CAT, f.level, BlockPos.ZERO)).thenReturn(true);
                if (condition == 1) when(f.level.getEntitiesOfClass(eq(Cat.class), any(AABB.class))).thenReturn(List.of(mock(Cat.class)));
                try (var sessions = mockConstruction(PoiSearchSession.class); var created = mockConstruction(Cat.class)) {
                    f.spawner.tick(f.level, true);
                    assertTrue(sessions.constructed().isEmpty());
                    assertEquals(condition == 0 ? 1 : 0, created.constructed().size());
                    if (condition != 2) verify(f.level).getEntitiesOfClass(Cat.class, new AABB(BlockPos.ZERO).inflate(16.0, 8.0, 16.0));
                    else verify(f.level, never()).getEntitiesOfClass(eq(Cat.class), any(AABB.class));
                    if (condition == 0) {
                        Cat cat = created.constructed().getFirst();
                        verify(cat).setPersistenceRequired();
                        verify(cat).finalizeSpawn(eq(f.level), any(), eq(EntitySpawnReason.NATURAL), isNull());
                        verify(f.level).addFreshEntityWithPassengers(cat);
                    }
                }
            }
        }
    }
}

package io.papermc.paper.entity.ai;

import ca.spottedleaf.moonrise.common.util.TickThread;
import com.google.common.collect.ImmutableList;
import com.mojang.datafixers.util.Pair;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.PoiCompetitorScan;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.entity.npc.villager.VillagerType;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.Level;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@VanillaFeature
class PoiCompetitorScanTest {
    private static final GlobalPos JOB_SITE = GlobalPos.of(Level.OVERWORLD, new BlockPos(32, 64, -48));
    private ServerLevel level;
    private PoiManager poiManager;
    private PoiSection section;
    private MockedStatic<TickThread> tickThread;

    @BeforeEach
    void setUp() {
        this.level = mock(ServerLevel.class);
        this.poiManager = mock(PoiManager.class);
        when(this.level.dimension()).thenReturn(Level.OVERWORLD);
        when(this.level.getPoiManager()).thenReturn(this.poiManager);
        when(this.poiManager.getType(any(BlockPos.class))).thenCallRealMethod();
        doThrow(new AssertionError("Competition must not load POI data"))
            .when(this.poiManager).getOrLoad(anyLong());
        doThrow(new AssertionError("Competition must not release POI tickets"))
            .when(this.poiManager).release(any(BlockPos.class));
        this.section = new PoiSection.Packed(true, List.of(new PoiRecord.Packed(
            JOB_SITE.pos(), BuiltInRegistries.POINT_OF_INTEREST_TYPE.getOrThrow(PoiTypes.FARMER), 0
        ))).unpack(() -> {});
        this.tickThread = mockStatic(TickThread.class);
        this.tickThread.when(() -> TickThread.isTickThreadFor(this.level, JOB_SITE.pos())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        this.tickThread.close();
    }

    @Test
    void resumesCompetitionAfterPoiLoadsWithoutLosingMemoriesOrTickets() {
        Villager body = villager(JOB_SITE, 10, VillagerProfession.FARMER);
        Villager other = villager(JOB_SITE, 20, VillagerProfession.FARMER);
        body.getBrain().setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, List.of(other));

        for (int tick = 0; tick < 3; tick++) {
            when(this.level.getGameTime()).thenReturn((long) tick);
            body.getBrain().tick(this.level, body);
            assertEquals(Optional.of(JOB_SITE), body.getBrain().getMemory(MemoryModuleType.JOB_SITE));
            assertEquals(Optional.of(JOB_SITE), other.getBrain().getMemory(MemoryModuleType.JOB_SITE));
        }

        when(this.poiManager.get(SectionPos.asLong(JOB_SITE.pos()))).thenReturn(Optional.of(this.section));
        when(this.level.getGameTime()).thenReturn(3L);
        body.getBrain().tick(this.level, body);

        assertFalse(body.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
        assertEquals(Optional.of(JOB_SITE), other.getBrain().getMemory(MemoryModuleType.JOB_SITE));
        assertEquals(0, this.section.getFreeTickets(JOB_SITE.pos()));
    }

    @ParameterizedTest
    @CsvSource({"20, 10, true", "10, 20, false", "10, 10, false"})
    void keepsExperienceAndTieRules(int bodyXp, int otherXp, boolean bodyWins) {
        Villager body = villager(JOB_SITE, bodyXp, VillagerProfession.FARMER);
        Villager other = villager(JOB_SITE, otherXp, VillagerProfession.FARMER);
        body.getBrain().setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, List.of(other));
        when(this.poiManager.get(SectionPos.asLong(JOB_SITE.pos()))).thenReturn(Optional.of(this.section));

        body.getBrain().tick(this.level, body);

        assertEquals(bodyWins, body.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
        assertEquals(!bodyWins, other.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
        assertEquals(0, this.section.getFreeTickets(JOB_SITE.pos()));
    }

    @Test
    void keepsMemoriesWhenLoadedSectionHasNoJobSite() {
        Villager body = villager(JOB_SITE, 10, VillagerProfession.FARMER);
        Villager other = villager(JOB_SITE, 20, VillagerProfession.FARMER);
        body.getBrain().setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, List.of(other));
        when(this.poiManager.get(SectionPos.asLong(JOB_SITE.pos())))
            .thenReturn(Optional.of(new PoiSection(() -> {})));

        body.getBrain().tick(this.level, body);

        assertTrue(body.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
        assertTrue(other.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
    }

    @Test
    void ignoresVillagersWithAnotherJobSiteOrProfession() {
        Villager body = villager(JOB_SITE, 10, VillagerProfession.FARMER);
        Villager anotherSite = villager(GlobalPos.of(Level.OVERWORLD, JOB_SITE.pos().above()), 20, VillagerProfession.FARMER);
        Villager anotherProfession = villager(JOB_SITE, 20, VillagerProfession.LIBRARIAN);
        body.getBrain().setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, List.of(anotherSite, anotherProfession));
        when(this.poiManager.get(SectionPos.asLong(JOB_SITE.pos()))).thenReturn(Optional.of(this.section));

        body.getBrain().tick(this.level, body);

        assertTrue(body.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
        assertTrue(anotherSite.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
        assertTrue(anotherProfession.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void doesNotReadPoiOutsideTheCurrentDimensionOrRegion(boolean anotherDimension) {
        Villager body = villager(JOB_SITE, 10, VillagerProfession.FARMER);
        Villager other = villager(JOB_SITE, 20, VillagerProfession.FARMER);
        body.getBrain().setMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES, List.of(other));
        if (anotherDimension) {
            when(this.level.dimension()).thenReturn(Level.NETHER);
        } else {
            this.tickThread.when(() -> TickThread.isTickThreadFor(this.level, JOB_SITE.pos())).thenReturn(false);
        }

        body.getBrain().tick(this.level, body);

        verifyNoInteractions(this.poiManager);
        assertTrue(body.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
        assertTrue(other.getBrain().hasMemoryValue(MemoryModuleType.JOB_SITE));
    }

    private static Villager villager(GlobalPos jobSite, int experience, ResourceKey<VillagerProfession> profession) {
        Villager villager = mock(Villager.class);
        Brain<Villager> brain = new Brain<>();
        brain.addActivity(Activity.CORE, ImmutableList.of(Pair.of(2, PoiCompetitorScan.create())), Set.of(), Set.of());
        brain.setMemory(MemoryModuleType.JOB_SITE, jobSite);
        when(villager.getBrain()).thenReturn(brain);
        when(villager.isAlive()).thenReturn(true);
        when(villager.getVillagerXp()).thenReturn(experience);
        when(villager.getVillagerData()).thenReturn(new VillagerData(
            BuiltInRegistries.VILLAGER_TYPE.getOrThrow(VillagerType.PLAINS),
            BuiltInRegistries.VILLAGER_PROFESSION.getOrThrow(profession), 1
        ));
        return villager;
    }
}

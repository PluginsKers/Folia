package ca.spottedleaf.moonrise.patches.poi_lookup;

import java.util.Optional;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.PoiTypeTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.GoToPotentialJobSite;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerData;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.level.Level;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class VillagerPoiReleaseTest {
    private static final BlockPos POS = new BlockPos(32, 64, 48);

    private static final class Fixture {
        final Villager body = mock(Villager.class);
        final Brain<?> brain = mock(Brain.class);
        final ServerLevel source = mock(ServerLevel.class), target = mock(ServerLevel.class);
        final MinecraftServer server = mock(MinecraftServer.class);
        final PoiManager manager = mock(PoiManager.class);
        final VillagerData data = mock(VillagerData.class);
        final Holder<PoiType> type = mock(Holder.Reference.class);

        Fixture() throws Exception {
            var field = LivingEntity.class.getDeclaredField("brain");
            field.setAccessible(true);
            field.set(body, brain);
            doReturn(brain).when(body).getBrain();
            when(body.level()).thenReturn(source);
            when(source.getServer()).thenReturn(server);
            when(server.getLevel(Level.NETHER)).thenReturn(target);
            when(target.getPoiManager()).thenReturn(manager);
            when(body.getVillagerData()).thenReturn(data);
            doCallRealMethod().when(body).releasePoi(any());
            doCallRealMethod().when(body).releaseAllPois();
        }

        void remember(MemoryModuleType<GlobalPos> memory) {
            when(brain.getMemory(memory)).thenReturn(Optional.of(GlobalPos.of(Level.NETHER, POS)));
        }
    }

    @Test
    void acceptedJobReleaseUsesOriginalProfessionWithoutReadingRetiredOrChangedEntity() throws Exception {
        Fixture f = new Fixture();
        f.remember(MemoryModuleType.JOB_SITE);
        Holder<VillagerProfession> holder = mock(Holder.Reference.class);
        VillagerProfession profession = mock(VillagerProfession.class);
        Predicate<Holder<PoiType>> held = type -> type == f.type;
        when(f.data.profession()).thenReturn(holder);
        when(holder.value()).thenReturn(profession);
        when(profession.heldJobSite()).thenReturn(held);
        f.body.releasePoi(MemoryModuleType.JOB_SITE);
        ArgumentCaptor<Predicate<Holder<PoiType>>> predicate = ArgumentCaptor.forClass(Predicate.class);
        verify(f.manager).releaseWhenLoaded(eq(POS), predicate.capture(), eq(false));
        when(f.body.getVillagerData()).thenThrow(new IllegalStateException("retired entity accessed"));
        when(f.body.isAlive()).thenReturn(false);
        when(f.brain.getMemory(MemoryModuleType.JOB_SITE)).thenReturn(Optional.empty());
        clearInvocations(f.body, f.brain);
        assertTrue(predicate.getValue().test(f.type));
        assertFalse(predicate.getValue().test(mock(Holder.Reference.class)));
        verifyNoInteractions(f.body, f.brain);
        verify(f.source, never()).getPoiManager();
    }

    @Test
    void allFourMemoriesRetainTheirTypePredicatesAndEachAcceptOneRelease() throws Exception {
        Fixture f = new Fixture();
        for (var memory : Villager.POI_MEMORIES.keySet()) f.remember(memory);
        Holder<VillagerProfession> profession = mock(Holder.Reference.class);
        VillagerProfession job = mock(VillagerProfession.class);
        when(f.data.profession()).thenReturn(profession);
        when(profession.value()).thenReturn(job);
        when(job.heldJobSite()).thenReturn(type -> type == f.type);
        when(f.type.is(PoiTypes.HOME)).thenReturn(true);
        when(f.type.is(PoiTypes.MEETING)).thenReturn(true);
        when(f.type.is(PoiTypeTags.ACQUIRABLE_JOB_SITE)).thenReturn(true);
        f.body.releaseAllPois();
        ArgumentCaptor<Predicate<Holder<PoiType>>> predicates = ArgumentCaptor.forClass(Predicate.class);
        verify(f.manager, times(4)).releaseWhenLoaded(eq(POS), predicates.capture(), eq(false));
        for (var predicate : predicates.getAllValues()) {
            assertTrue(predicate.test(f.type));
            assertFalse(predicate.test(mock(Holder.Reference.class)));
        }
    }

    @Test
    void missingMemoryOrMissingTargetWorldDoesNotCreateRelease() throws Exception {
        Fixture f = new Fixture();
        when(f.brain.getMemory(MemoryModuleType.HOME)).thenReturn(Optional.empty());
        f.body.releasePoi(MemoryModuleType.HOME);
        f.remember(MemoryModuleType.HOME);
        when(f.server.getLevel(Level.NETHER)).thenReturn(null);
        f.body.releasePoi(MemoryModuleType.HOME);
        verifyNoInteractions(f.manager);
    }

    @Test
    void potentialJobStopUsesTargetWorldAndKeepsUnconditionalDebugPolicy() throws Exception {
        Fixture f = new Fixture();
        f.remember(MemoryModuleType.POTENTIAL_JOB_SITE);
        class Behavior extends GoToPotentialJobSite {
            Behavior() { super(1.0F); }
            void finish() { super.stop(f.source, f.body, 0); }
        }
        new Behavior().finish();
        ArgumentCaptor<Predicate<Holder<PoiType>>> predicate = ArgumentCaptor.forClass(Predicate.class);
        verify(f.manager).releaseWhenLoaded(eq(POS), predicate.capture(), eq(true));
        assertTrue(predicate.getValue().test(f.type));
        verify(f.brain).eraseMemory(MemoryModuleType.POTENTIAL_JOB_SITE);
        verify(f.source, never()).getPoiManager();
        verify(f.source, never()).debugSynchronizers();
    }
}

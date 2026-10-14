package ca.spottedleaf.moonrise.patches.poi_lookup;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.SetClosestHomeAsWalkTarget;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class PoiHomeBehaviorTest {
    private final PathfinderMob body = mock(PathfinderMob.class);
    private final ServerLevel level = mock(ServerLevel.class);
    private final Brain<?> brain = mock(Brain.class);

    private void prepare() throws Exception {
        doReturn(brain).when(body).getBrain();
        when(body.blockPosition()).thenReturn(BlockPos.ZERO);
        when(level.getGameTime()).thenReturn(100L);
        when(brain.getMemoryInternal(MemoryModuleType.WALK_TARGET)).thenReturn(Optional.empty());
        when(brain.getMemoryInternal(MemoryModuleType.HOME)).thenReturn(Optional.empty());
        when(brain.getActiveNonCoreActivity()).thenReturn(Optional.of(Activity.REST));
        var manager = mock(PoiManager.class);
        var height = SectionStorage.class.getDeclaredField("levelHeightAccessor");
        height.setAccessible(true);
        height.set(manager, LevelHeightAccessor.create(-64, 128));
        when(level.getPoiManager()).thenReturn(manager);
    }

    @Test
    void warmEmptyOrNearbyHomeStillAllowsOtherRunOneBehaviors() throws Exception {
        prepare();
        try (var tasks = mockStatic(PoiSearchTask.class)) {
            PoiRecord nearby = mock(PoiRecord.class);
            when(nearby.getPos()).thenReturn(BlockPos.ZERO);
            for (List<PoiRecord> results : List.of(List.<PoiRecord>of(), List.of(nearby))) {
                tasks.when(() -> PoiSearchTask.start(any(), any(), any(), any(), any())).thenAnswer(call -> {
                    call.<Consumer<List<PoiRecord>>>getArgument(3).accept(results);
                    call.<Runnable>getArgument(4).run();
                    return null;
                });
                assertFalse(SetClosestHomeAsWalkTarget.create(0.4F).tryStart(level, body, 100L));
            }
        }
    }

    @Test
    void pendingHomeWalkCannotCommitAfterRestActivityChanges() throws Exception {
        prepare();
        try (var tasks = mockStatic(PoiSearchTask.class)) {
            tasks.when(() -> PoiSearchTask.start(any(), any(), any(), any(), any())).thenAnswer(call -> {
                Predicate<Mob> valid = call.getArgument(2);
                assertTrue(valid.test(body));
                when(brain.getActiveNonCoreActivity()).thenReturn(Optional.of(Activity.WORK));
                assertFalse(valid.test(body));
                return null;
            });
            assertTrue(SetClosestHomeAsWalkTarget.create(0.4F).tryStart(level, body, 100L));
        }
    }
}

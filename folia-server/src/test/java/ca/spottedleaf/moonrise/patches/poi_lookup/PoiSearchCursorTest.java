package ca.spottedleaf.moonrise.patches.poi_lookup;

import ca.spottedleaf.moonrise.common.util.CoordinateUtils;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiRecord;
import net.minecraft.world.entity.ai.village.poi.PoiSection;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.chunk.storage.SectionStorage;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class PoiSearchCursorTest {
    private final Holder<PoiType> type = Holder.direct(new PoiType(Set.of(), 1, 1));
    private final Map<Long, PoiSection> sections = new HashMap<>();

    private PoiManager manager() throws Exception {
        PoiManager manager = mock(PoiManager.class);
        Field height = SectionStorage.class.getDeclaredField("levelHeightAccessor");
        height.setAccessible(true);
        height.set(manager, LevelHeightAccessor.create(-64, 128));
        return manager;
    }

    private BlockPos add(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        sections.computeIfAbsent(CoordinateUtils.getChunkSectionKey(x >> 4, y >> 4, z >> 4), key -> new PoiSection(() -> {})).add(pos, type);
        return pos;
    }

    private Optional<PoiSection> section(long key) { return Optional.ofNullable(sections.get(key)); }

    private void resumeEverySection(PoiAccess.Search cursor) {
        Set<Long> ready = new HashSet<>();
        LongFunction<Optional<PoiSection>> pending = key -> ready.add(key) ? null : section(key);
        int resumes = 0;
        while (!cursor.advance(pending)) assertTrue(++resumes < 10000);
        assertTrue(cursor.advance(key -> { fail("completed cursor read again"); return null; }));
    }

    @Test
    void nearestSuspensionPreservesPredicateVisitsResultsAndTieOrder() throws Exception {
        add(-16, 0, 0); add(16, 0, 0); add(0, 0, -16); add(0, 0, 16); add(0, 16, 0);
        add(40, 0, 40); add(2, 50, 2);
        List<BlockPos> baselineCalls = new ArrayList<>(), resumedCalls = new ArrayList<>();
        var baseline = new PoiAccess.NearestSearch(manager(), holder -> true, pos -> { baselineCalls.add(pos); return true; }, BlockPos.ZERO, 48, 48 * 48, PoiManager.Occupancy.HAS_SPACE, 3);
        var resumed = new PoiAccess.NearestSearch(manager(), holder -> true, pos -> { resumedCalls.add(pos); return true; }, BlockPos.ZERO, 48, 48 * 48, PoiManager.Occupancy.HAS_SPACE, 3);
        assertTrue(baseline.advance(this::section));
        resumeEverySection(resumed);
        assertEquals(baselineCalls, resumedCalls);
        assertEquals(baseline.result(), resumed.result());
        assertEquals(List.of(new BlockPos(0, 0, -16), new BlockPos(-16, 0, 0), new BlockPos(0, 16, 0)), resumed.result().stream().map(PoiRecord::getPos).toList());
    }

    @Test
    void anySuspensionPreservesZYXTraversalCapAndSideEffects() throws Exception {
        add(16, 0, -16); add(-16, 16, 0); add(-16, 0, 0); add(0, 0, 0);
        List<BlockPos> visited = new ArrayList<>();
        var search = new PoiAccess.AnySearch(manager(), holder -> true, (holder, pos) -> { visited.add(pos); return true; }, BlockPos.ZERO, 48, 48 * 48, PoiManager.Occupancy.ANY, 3);
        resumeEverySection(search);
        assertEquals(List.of(new BlockPos(16, 0, -16), new BlockPos(-16, 0, 0), new BlockPos(-16, 16, 0)), visited);
        assertEquals(visited, search.result().stream().map(PoiRecord::getPos).toList());
    }

    @Test
    void loadedEmptyIsNotPendingAndColdTraversalRequestsOnlyItsNextSection() throws Exception {
        add(1, 0, 1);
        Set<Long> requestedChunks = new HashSet<>();
        var search = new PoiAccess.NearestSearch(manager(), holder -> true, null, BlockPos.ZERO, 48, Double.MAX_VALUE, PoiManager.Occupancy.ANY, 1);
        assertFalse(search.advance(key -> {
            requestedChunks.add(CoordinateUtils.getChunkKey(CoordinateUtils.getChunkSectionX(key), CoordinateUtils.getChunkSectionZ(key)));
            return null;
        }));
        assertEquals(1, requestedChunks.size(), "suspension must not prefetch the entire square");
        assertTrue(search.advance(key -> {
            requestedChunks.add(CoordinateUtils.getChunkKey(CoordinateUtils.getChunkSectionX(key), CoordinateUtils.getChunkSectionZ(key)));
            return section(key);
        }));
        assertEquals(1, search.result().size());
        var empty = new PoiAccess.AnySearch(manager(), holder -> true, null, BlockPos.ZERO, 0, 0, PoiManager.Occupancy.ANY, 1);
        assertTrue(empty.advance(key -> Optional.empty()));
        assertTrue(empty.result().isEmpty());
    }

    private PoiManager loadedOnlyManager() throws Exception {
        PoiManager manager = manager();
        when(manager.get(anyLong())).thenAnswer(invocation -> {
            PoiSection section = sections.get(invocation.<Long>getArgument(0));
            return section == null ? null : Optional.of(section);
        });
        return manager;
    }

    @Test
    void nearestLoadedOnlySkipsColdSectionAndFindsLaterLoadedRecord() throws Exception {
        BlockPos target = add(16, 0, 0);
        PoiManager manager = loadedOnlyManager();
        List<PoiRecord> result = new ArrayList<>();
        PoiAccess.findNearestPoiRecords(manager, holder -> true, pos -> true, BlockPos.ZERO, 48, 48 * 48,
            PoiManager.Occupancy.ANY, false, 1, result);
        assertEquals(List.of(target), result.stream().map(PoiRecord::getPos).toList());
        verify(manager).get(CoordinateUtils.getChunkSectionKey(0, 0, 0));
        verify(manager, never()).getOrLoad(anyLong());
    }

    @Test
    void anyLoadedOnlySkipsColdSectionWithPositionPredicate() throws Exception {
        BlockPos target = add(0, 0, 0);
        PoiManager manager = loadedOnlyManager();
        List<PoiRecord> result = new ArrayList<>();
        PoiAccess.findAnyPoiRecords(manager, holder -> true, pos -> true, BlockPos.ZERO, 48, 48 * 48,
            PoiManager.Occupancy.ANY, false, 1, result);
        assertEquals(List.of(target), result.stream().map(PoiRecord::getPos).toList());
        verify(manager).get(CoordinateUtils.getChunkSectionKey(-3, -3, -3));
        verify(manager, never()).getOrLoad(anyLong());
    }

    @Test
    void anyLoadedOnlySkipsColdSectionWithTypeAndPositionPredicate() throws Exception {
        BlockPos target = add(0, 0, 0);
        PoiManager manager = loadedOnlyManager();
        List<PoiRecord> result = new ArrayList<>();
        PoiAccess.findAnyPoiRecords(manager, holder -> true, (holder, pos) -> true, BlockPos.ZERO, 48, 48 * 48,
            PoiManager.Occupancy.ANY, false, 1, result);
        assertEquals(List.of(target), result.stream().map(PoiRecord::getPos).toList());
        verify(manager).get(CoordinateUtils.getChunkSectionKey(-3, -3, -3));
        verify(manager, never()).getOrLoad(anyLong());
    }

    @Test
    void exactTakeQueryDoesNotVisitAdjacentChunkAtBorder() throws Exception {
        BlockPos target = add(15, 0, 15);
        var search = new PoiAccess.AnySearch(manager(), holder -> true, (holder, pos) -> pos.equals(target), target, 0, 0, PoiManager.Occupancy.HAS_SPACE, 1);
        List<Long> visits = new ArrayList<>();
        assertTrue(search.advance(key -> { visits.add(key); return section(key); }));
        assertEquals(List.of(CoordinateUtils.getChunkSectionKey(0, 0, 0)), visits);
        assertEquals(target, search.result().getFirst().getPos());
    }
}

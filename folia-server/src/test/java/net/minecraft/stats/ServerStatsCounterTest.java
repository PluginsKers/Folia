package net.minecraft.stats;

import com.google.gson.JsonParser;
import com.mojang.datafixers.DataFixer;
import io.papermc.paper.storage.PlayerStatisticsStorage;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import org.bukkit.support.environment.VanillaFeature;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@VanillaFeature
public class ServerStatsCounterTest {
    @Test
    void unknownStatisticsRetainTheOriginalPartialDecode() {
        MinecraftServer server = mock(MinecraftServer.class);
        DataFixer fixer = mock(DataFixer.class);
        when(fixer.update(any(), any(), anyInt(), anyInt())).thenAnswer(call -> call.getArgument(1));
        when(server.getFixerUpper()).thenReturn(fixer);
        var json = JsonParser.parseString("""
            {"stats": {
              "minecraft:custom": {"minecraft:jump": 7, "removed:unknown": 99},
              "removed:type": {"removed:entry": 1},
              "minecraft:mined": {"minecraft:stone": 4}
            }}
            """);
        ServerStatsCounter counter = new ServerStatsCounter(server, UUID.randomUUID(), mock(PlayerStatisticsStorage.class), Optional.of(json));
        assertEquals(7, counter.getValue(Stats.CUSTOM.get(Stats.JUMP)));
        assertEquals(4, counter.getValue(Stats.BLOCK_MINED.get(net.minecraft.world.level.block.Blocks.STONE)));
        assertEquals(0, counter.getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)));
    }
}

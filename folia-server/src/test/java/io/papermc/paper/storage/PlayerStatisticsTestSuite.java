package io.papermc.paper.storage;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({PlayerStatisticsStorageTest.class, org.bukkit.craftbukkit.CraftStatisticSnapshotTest.class, net.minecraft.stats.ServerStatsCounterTest.class})
public class PlayerStatisticsTestSuite {
}

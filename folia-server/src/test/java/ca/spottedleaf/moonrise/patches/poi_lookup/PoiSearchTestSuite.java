package ca.spottedleaf.moonrise.patches.poi_lookup;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses(value = {PoiSearchCursorTest.class, PoiSearchTaskTest.class, PoiHomeBehaviorTest.class,
    CatSpawnerTest.class, io.papermc.paper.threadedregions.CatSpawnerLifecycleTest.class, NearestBedSensorTest.class},
    names = "io.papermc.paper.entity.ai.PoiCompetitorScanTest")
public class PoiSearchTestSuite {
}

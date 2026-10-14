package ca.spottedleaf.moonrise.patches.poi_lookup;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({PoiSearchCursorTest.class, PoiSearchTaskTest.class, PoiChunkLeaseTest.class, PoiHomeBehaviorTest.class,
    NearestBedSensorTest.class})
public class PoiSearchTestSuite {
}

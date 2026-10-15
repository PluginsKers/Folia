package ca.spottedleaf.moonrise.patches.poi_lookup;

import org.junit.platform.suite.api.SelectClasses;
import org.junit.platform.suite.api.Suite;

@Suite
@SelectClasses({PoiChunkLeaseTest.class, PoiReleaseTest.class, VillagerPoiReleaseTest.class})
public class PoiReleaseTestSuite {
}

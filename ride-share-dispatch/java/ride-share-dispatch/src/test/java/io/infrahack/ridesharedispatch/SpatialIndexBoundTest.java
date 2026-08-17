package io.infrahack.ridesharedispatch;

import io.infrahack.ridesharedispatch.domain.AgentId;
import io.infrahack.ridesharedispatch.domain.GeoPoint;
import io.infrahack.ridesharedispatch.service.SpatialIndex;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class SpatialIndexBoundTest extends AbstractIntegrationTest {

    @Autowired private SpatialIndex spatialIndex;

    @Test
    void hotCellReturnsOnlyTheConfiguredOversampleBudget() {
        GeoPoint point = new GeoPoint(37.7749, -122.4194);
        IntStream.range(0, 200).forEach(i -> spatialIndex.upsert(AgentId.newId(), point));

        assertThat(spatialIndex.nearby(point, 0, 5)).hasSizeLessThanOrEqualTo(15);
    }
}

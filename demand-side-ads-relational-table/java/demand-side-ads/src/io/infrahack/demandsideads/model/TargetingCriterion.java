package io.infrahack.demandsideads.model;

import io.infrahack.demandsideads.enums.TargetingDimension;
import io.infrahack.demandsideads.enums.TargetingOperator;

public record TargetingCriterion(String criterionId,
                                 TargetingDimension dimension,
                                 TargetingOperator operator,
                                 String valueType,
                                 String valueId) {
}

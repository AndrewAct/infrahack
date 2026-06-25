package io.infrahack.parkinglot.model;

import io.infrahack.parkinglot.enums.GateType;

/**
 * An entry or exit barrier. {@code nearestLevel} lets assignment strategies
 * steer a vehicle toward the level closest to where it entered.
 */
public record Gate(String id, GateType type, int nearestLevel) {
}

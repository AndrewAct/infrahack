package io.infrahack.parkinglot.enums;

/**
 * Physical spot classes, ordered smallest to largest footprint.
 * EV is a COMPACT-sized bay that additionally has a charger, so it can
 * physically hold a CAR or MOTORCYCLE but is reserved for electric vehicles
 * by policy (see {@code Vehicle#spotPreference}).
 */
public enum SpotType {
    MOTORCYCLE,
    COMPACT,
    EV,
    LARGE
}

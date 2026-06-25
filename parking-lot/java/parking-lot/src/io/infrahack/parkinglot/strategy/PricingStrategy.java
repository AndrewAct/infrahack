package io.infrahack.parkinglot.strategy;

import io.infrahack.parkinglot.model.Money;
import io.infrahack.parkinglot.model.ParkingTicket;

import java.time.Instant;

/** Computes the fee for a stay. Pure function of the ticket and an exit time. */
public interface PricingStrategy {
    Money computeFee(ParkingTicket ticket, Instant exitTime);
}

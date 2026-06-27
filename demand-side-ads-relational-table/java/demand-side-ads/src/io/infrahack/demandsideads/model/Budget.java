package io.infrahack.demandsideads.model;

import java.math.BigDecimal;

public record Budget(BigDecimal amount,
                     String currency) {
}

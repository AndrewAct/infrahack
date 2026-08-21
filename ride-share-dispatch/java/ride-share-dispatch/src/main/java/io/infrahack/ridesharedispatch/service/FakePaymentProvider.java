package io.infrahack.ridesharedispatch.service;

import io.infrahack.ridesharedispatch.config.DispatchProperties;
import io.infrahack.ridesharedispatch.domain.Money;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Fake external provider with a durable idempotency ledger. The ledger survives an app
 * restart, which is the failure boundary the timeout/reconciliation example teaches.
 */
@Component
public class FakePaymentProvider implements PaymentProvider {

    private final JdbcTemplate jdbc;
    private final Set<String> forceTimeoutOnce = ConcurrentHashMap.newKeySet();
    private final double randomTimeoutRate;

    public FakePaymentProvider(DispatchProperties properties, JdbcTemplate jdbc) {
        this.randomTimeoutRate = properties.payment().simulatedTimeoutRate();
        this.jdbc = jdbc;
    }

    @Override
    public ChargeOutcome charge(String operationId, Money amount) {
        ChargeOutcome computed = amount.cents() > 0 ? ChargeOutcome.SUCCEEDED : ChargeOutcome.FAILED;
        jdbc.update("""
                        INSERT INTO fake_payment_provider_charges (operation_id, amount_cents, outcome)
                        VALUES (?, ?, ?) ON CONFLICT (operation_id) DO NOTHING
                        """, operationId, amount.cents(), computed.name());

        var stored = jdbc.queryForMap("""
                SELECT amount_cents, outcome FROM fake_payment_provider_charges WHERE operation_id = ?
                """, operationId);
        if (((Number) stored.get("amount_cents")).longValue() != amount.cents()) {
            throw new IllegalStateException("Payment operation id reused with a different amount: " + operationId);
        }
        ChargeOutcome trueOutcome = ChargeOutcome.valueOf(stored.get("outcome").toString());
        if (forceTimeoutOnce.remove(operationId) || rolledRandomTimeout()) return ChargeOutcome.TIMEOUT;
        return trueOutcome;
    }

    private boolean rolledRandomTimeout() {
        return randomTimeoutRate > 0 && ThreadLocalRandom.current().nextDouble() < randomTimeoutRate;
    }

    public void simulateTimeoutOnNextCall(String operationId) {
        forceTimeoutOnce.add(operationId);
    }

    public int chargeComputationCount(String operationId) {
        Integer count = jdbc.queryForObject(
                "SELECT count(*) FROM fake_payment_provider_charges WHERE operation_id = ?", Integer.class, operationId);
        return count == null ? 0 : count;
    }
}

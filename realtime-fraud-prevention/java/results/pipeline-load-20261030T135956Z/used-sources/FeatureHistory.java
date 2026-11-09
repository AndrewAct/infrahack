package dev.infrahack.fraud;

import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.common.state.*;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import java.util.ArrayList;
import java.util.Arrays;

/** Finalize timestamps before emitting to CEP; snapshot S before later equal-time positions. */
public class FeatureHistory extends KeyedProcessFunction<String, Payment, Payment> {
    private final long coverageStart;
    private transient MapState<Long, Payment[]> pending;
    private transient ListState<Payment> history;
    private transient ValueState<Long> conflictUntil;

    public FeatureHistory(long coverageStart) { this.coverageStart = coverageStart; }

    @Override public void open(OpenContext context) throws Exception {
        pending = getRuntimeContext().getMapState(new MapStateDescriptor<>("ordered-buffer", Long.class, Payment[].class));
        history = getRuntimeContext().getListState(new ListStateDescriptor<>("ten-minute-history", Payment.class));
        conflictUntil = getRuntimeContext().getState(new ValueStateDescriptor<>("conflict-until", Long.class));
    }

    @Override public void processElement(Payment p, Context ctx, Collector<Payment> out) throws Exception {
        if (p.conflict) {
            Long prior = conflictUntil.value();
            conflictUntil.update(Math.max(prior == null ? Long.MIN_VALUE : prior, p.conflictUntil));
            ctx.timerService().registerEventTimeTimer(p.conflictUntil);
            return;
        }
        Payment[] old = pending.get(p.occurredAt);
        Payment[] bucket = old == null ? new Payment[1] : Arrays.copyOf(old, old.length + 1);
        bucket[bucket.length - 1] = p;
        pending.put(p.occurredAt, bucket);
        ctx.timerService().registerEventTimeTimer(p.occurredAt);
    }

    @Override public void onTimer(long t, OnTimerContext ctx, Collector<Payment> out) throws Exception {
        var retained = new ArrayList<Payment>();
        for (Payment p : history.get()) if (p.occurredAt > t - 600_000L) retained.add(p);
        Payment[] bucket = pending.get(t);
        if (bucket != null) {
            Arrays.sort(bucket, Payment.ORDER);
            for (Payment p : bucket) {
                retained.add(p);
                if (p.status.equals("authorized")) {
                    Long until = conflictUntil.value();
                    p.snapshot = Features.at(retained, p, coverageStart, until == null ? Long.MIN_VALUE : until);
                }
                out.collect(p);
                ctx.timerService().registerEventTimeTimer(t + 600_000L);
            }
            pending.remove(t);
        }
        history.update(retained);
        Long until = conflictUntil.value();
        if (until != null && t >= until) conflictUntil.clear();
    }
}

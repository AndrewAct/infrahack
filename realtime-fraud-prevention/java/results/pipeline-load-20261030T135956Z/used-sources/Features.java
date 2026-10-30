package dev.infrahack.fraud;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

public class Features implements Serializable {
    public int attempts;
    public int failures;
    public double failureFraction;
    public int devices;
    public int ips;
    public long amountMinor;
    public double medianGapSeconds;
    public boolean complete;
    public long historyStart;
    public Features() {}

    public static Features at(List<Payment> history, Payment success, long coverageStart, long conflictUntil) {
        Features f = new Features();
        f.historyStart = success.occurredAt - 600_000L;
        f.complete = coverageStart <= f.historyStart && success.occurredAt >= conflictUntil;
        var devices = new HashSet<String>();
        var ips = new HashSet<String>();
        var gaps = new ArrayList<Long>();
        Payment previous = null;
        for (Payment p : history) {
            f.attempts++;
            if (p.status.equals("declined")) f.failures++;
            devices.add(p.device);
            ips.add(p.ip);
            f.complete &= p.device != null && p.ip != null && p.currency.equals(success.currency);
            f.amountMinor = Math.addExact(f.amountMinor, p.amountMinor);
            if (previous != null) gaps.add(p.occurredAt - previous.occurredAt);
            previous = p;
        }
        devices.remove(null);
        ips.remove(null);
        f.devices = devices.size();
        f.ips = ips.size();
        f.failureFraction = (double) f.failures / f.attempts;
        gaps.sort(Long::compare);
        int m = gaps.size() / 2;
        f.medianGapSeconds = gaps.isEmpty() ? 0 : gaps.size() % 2 == 1
            ? gaps.get(m) / 1000.0 : (gaps.get(m - 1) / 2.0 + gaps.get(m) / 2.0) / 1000.0;
        return f;
    }

    public double[] vector(double durationSeconds) {
        if (!complete) return null;
        return new double[] {scale(attempts, 100), scale(failures, 100), failureFraction,
            scale(devices, 20), scale(ips, 20), scale(amountMinor, 1_000_000),
            scale(medianGapSeconds, 600), scale(durationSeconds, 600)};
    }

    private static double scale(double value, double cap) {
        return (float) (Math.log1p(Math.min(value, cap)) / Math.log1p(cap));
    }
}

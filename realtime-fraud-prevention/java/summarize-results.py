#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = []
# ///
"""Aggregate retained samples; all quantiles use the nearest-rank definition."""

import argparse
import collections
import json
import math
import statistics
from datetime import datetime
from pathlib import Path


def quantile(values, fraction):
    ordered = sorted(values)
    return ordered[max(0, math.ceil(fraction * len(ordered)) - 1)]


def retrieval(directory, replacement=None):
    groups = collections.defaultdict(list)
    for line in (directory / "samples.jsonl").read_text().splitlines():
        row = json.loads(line)
        groups[row["n"], row["d"], row["mode"]].append(row)
    if replacement:
        extra = collections.defaultdict(list)
        for line in (replacement / "samples.jsonl").read_text().splitlines():
            row = json.loads(line)
            extra[row["n"], row["d"], row["mode"]].append(row)
        groups.update(extra)
        (directory / "aggregation-inputs.json").write_text(json.dumps({"primary": str(directory), "replacement": str(replacement), "replaced_groups": [list(k) for k in extra]}, indent=2) + "\n")
    expected = {(n, d, mode) for n in (10000, 100000) for d in (8, 64, 256, 768)
                for mode in (["exact", "hnsw-32", "hnsw-100", "hnsw-200"] if d == 256 else ["exact", "hnsw-100"])}
    if set(groups) != expected or any(len(rows) != 36 for rows in groups.values()):
        raise ValueError("incomplete matrix: every point/mode must have all 36 samples")
    result = []
    for (n, d, mode), rows in sorted(groups.items()):
        times = [r["millis"] for r in rows]
        per_rep = [statistics.median(r["millis"] for r in rows if r["rep"] == rep) for rep in range(3)]
        result.append({"n": n, "d": d, "mode": mode, "samples": len(times),
                       "median_ms": statistics.median(times), "p95_ms": quantile(times, .95),
                       "p99_ms": quantile(times, .99), "repeat_medians_ms": per_rep,
                       "mean_recall": statistics.mean(r["recall"] for r in rows),
                       "minimum_recall": min(r["recall"] for r in rows)})
    (directory / "aggregate.json").write_text(json.dumps(result, indent=2) + "\n")
    for row in result:
        print(row)


def load(directory):
    summary = json.loads((directory / "load-summary.json").read_text())
    schedule = {r["episode"]: r for r in map(json.loads, (directory / "schedule.jsonl").read_text().splitlines())}
    committed = list(map(json.loads, (directory / "committed.jsonl").read_text().splitlines()))
    result = []
    for stage in range(4):
        rows = [r for r in committed if schedule[r["episode"]]["stage"] == stage]
        ages = [r["observed_ms"] - r["emitted_ms"] for r in rows]
        local_case_ms = [int(datetime.fromisoformat(r["doc"]["knowledge_time"]).timestamp() * 1000) for r in rows]
        event_ms = [int(datetime.fromisoformat(r["doc"]["feature_cutoff"]).timestamp() * 1000) for r in rows]
        lag = [r["lag_ms"] for r in schedule.values() if r["stage"] == stage]
        result.append({"stage": stage, "count": len(rows), "terminal_emission_to_committed_observation_ms_median": statistics.median(ages),
                       "terminal_emission_to_committed_observation_ms_p95": quantile(ages, .95),
                       "event_to_local_case_ms_median": statistics.median(t - e for t, e in zip(local_case_ms, event_ms)),
                       "local_case_to_committed_observation_ms_median": statistics.median(r["observed_ms"] - t for r, t in zip(rows, local_case_ms)),
                       "generator_lag_ms_p95": quantile(lag, .95), "generator_lag_ms_max": max(lag)})
    summary["by_input_stage"] = result
    baseline = list(map(json.loads, (directory / "historical-baseline.jsonl").read_text().splitlines()))
    historical = list(map(json.loads, (directory / "historical-queries.jsonl").read_text().splitlines()))
    baseline_ids = {r["query"]: r["ids"] for r in baseline}
    summary["historical_results_match_frozen_baseline_fraction"] = statistics.mean(r["ids"] == baseline_ids[r["query"]] for r in historical)
    summary["historical_baseline"] = {"samples": len(baseline), "median_ms": statistics.median(r["millis"] for r in baseline), "p95_ms": quantile([r["millis"] for r in baseline], .95)}
    for stage, result_row in enumerate(result):
        period = summary["stages"][stage]
        rows = [r for r in historical if period["wall_start_ms"] <= r["before_ms"] <= period["wall_end_ms"]]
        if rows:
            times = [r["millis"] for r in rows]
            result_row["historical_query_samples"] = len(rows)
            result_row["historical_query_median_ms"] = statistics.median(times)
            result_row["historical_query_p95_ms"] = quantile(times, .95)
            result_row["historical_query_p99_ms"] = quantile(times, .99)
    visibility = list(map(json.loads, (directory / "visibility.jsonl").read_text().splitlines()))
    end = summary["stages"][-1]["wall_end_ms"]
    summary["drain_after_last_stage_ms"] = visibility[-1]["after_ms"] - end
    # RSS is a resident-process snapshot; ps %CPU can exceed 100 on multicore.
    processes = collections.defaultdict(list)
    timestamp = 0
    for line in (directory / "process-samples.txt").read_text().splitlines():
        parts = line.split()
        if len(parts) == 1:
            timestamp = int(parts[0]) * 1000
        if len(parts) == 4:
            pid, rss, cpu, _ = parts
            processes[pid].append((int(rss), float(cpu), timestamp))
    summary["process_snapshots"] = {pid: {"samples": len(rows), "peak_rss_mib": max(r[0] for r in rows) / 1024,
                                                  "median_ps_cpu_percent": statistics.median(r[1] for r in rows),
                                                  "peak_ps_cpu_percent": max(r[1] for r in rows)} for pid, rows in processes.items()}
    summary["stage_process_snapshots"] = []
    for period in summary["stages"]:
        for pid, rows in processes.items():
            scoped = [r for r in rows if period["wall_start_ms"] <= r[2] <= period["wall_end_ms"]]
            if scoped:
                summary["stage_process_snapshots"].append({"stage": period["stage"], "pid": pid, "samples": len(scoped),
                    "peak_rss_mib": max(r[0] for r in scoped) / 1024, "median_ps_cpu_percent": statistics.median(r[1] for r in scoped)})
    (directory / "aggregate.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps({k: v for k, v in summary.items() if k != "node_stats"}, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=["retrieval", "load"])
    parser.add_argument("directory", type=Path)
    parser.add_argument("--replacement", type=Path)
    args = parser.parse_args()
    if args.kind == "retrieval":
        retrieval(args.directory, args.replacement)
    else:
        load(args.directory)

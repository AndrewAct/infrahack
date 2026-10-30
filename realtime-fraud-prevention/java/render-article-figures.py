#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["matplotlib==3.10.7"]
# ///
"""Publication figures generated from retained aggregate measurements."""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.patches import FancyArrowPatch, FancyBboxPatch

parser = argparse.ArgumentParser()
parser.add_argument("retrieval", type=Path)
parser.add_argument("output", type=Path)
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 11})

rows = json.loads((args.retrieval / "aggregate.json").read_text())
lookup = {(r["n"], r["d"], r["mode"]): r for r in rows}
cells = []
for n in (10000, 100000):
    for d in (8, 64, 256, 768):
        exact, ann = lookup[n, d, "exact"], lookup[n, d, "hnsw-100"]
        cells.append([f"{n:,}", str(d), f"{exact['median_ms']:.2f}", f"{ann['median_ms']:.2f}", f"{100 * ann['mean_recall']:.1f}%"])
fig, ax = plt.subplots(figsize=(11, 5.8))
ax.axis("off")
ax.set_title("Filtered historical retrieval on one local OpenSearch node", loc="left", weight="bold", pad=20)
table = ax.table(cellText=cells, colLabels=["Eligible cases", "Dimensions", "Exact median (ms)", "HNSW median (ms)", "Mean Recall@10"],
                 cellLoc="center", loc="center", bbox=[0, .13, 1, .79])
table.auto_set_font_size(False)
table.set_fontsize(11)
for (r, c), cell in table.get_celld().items():
    cell.set_edgecolor("#d6dde3")
    if r == 0:
        cell.set_facecolor("#183c52"); cell.set_text_props(color="white", weight="bold")
    else:
        cell.set_facecolor("#eef4f6" if r % 2 else "white")
fig.text(.08, .05, "Warm cache · 12 queries × 3 repetitions per mode · cosine · K=10 · ef_search=100\nSeeded synthetic vectors; recall measures distance fidelity, not fraud-detection quality.", fontsize=10, color="#435360")
fig.savefig(args.output / "part-2-retrieval-results.png", dpi=200, bbox_inches="tight")
fig.savefig(args.output / "part-2-retrieval-results.svg", bbox_inches="tight")
plt.close(fig)

fig, ax = plt.subplots(figsize=(13, 4.3))
ax.set(xlim=(0, 13), ylim=(-.5, 4.2)); ax.axis("off")
nodes = [(1.4, 3, "Raw payments\nKafka"), (4.7, 3, "Validate / admit\nbusiness dedup"), (8, 3, "Ordered features\nFlink CEP"),
         (11.3, 3, "Committed cases\nKafka"), (11.3, 1, "Create-only\nindexer"), (7.8, 1, "Historical cases\nOpenSearch"), (4.3, 1, "Conflict quarantine\nKafka")]
for x, y, label in nodes:
    ax.add_patch(FancyBboxPatch((x-1.2, y-.52), 2.4, 1.04, boxstyle="round,pad=0.07", facecolor="#eef4f6", edgecolor="#183c52", linewidth=1.4))
    ax.text(x, y, label, ha="center", va="center", color="#183c52")
for start, end in [((2.68, 3),(3.42, 3)), ((5.98, 3),(6.72, 3)), ((9.28, 3),(10.02, 3)), ((11.3,2.4),(11.3,1.6)), ((10.02,1),(9.08,1))]:
    ax.add_patch(FancyArrowPatch(start, end, arrowstyle="-|>", mutation_scale=14, linewidth=1.5, color="#526976"))
ax.plot([11.3, 11.3, 4.3], [.4, .05, .05], color="#526976", linewidth=1.5)
ax.add_patch(FancyArrowPatch((4.3,.05), (4.3,.4), arrowstyle="-|>", mutation_scale=14, linewidth=1.5, color="#526976"))
ax.text(7.8, -.25, "Persist conflict before advancing source offsets", fontsize=9, ha="center", color="#526976")
ax.set_title("From payment events to immutable, searchable risk cases", loc="left", weight="bold")
fig.savefig(args.output / "part-1-pipeline.png", dpi=200, bbox_inches="tight")
fig.savefig(args.output / "part-1-pipeline.svg", bbox_inches="tight")
plt.close(fig)

fig, ax = plt.subplots(figsize=(11, 3.5))
ax.axis("off")
ax.set_title("Correctness and recovery: recorded local results", loc="left", weight="bold", pad=16)
table = ax.table(cellText=[
    ["Event-time oracle", "26 traces / 118 matching cases"],
    ["Exactly-once recovery", "2 physical records / 1 committed case"],
    ["At-least-once recovery", "2 physical records / 2 committed cases"],
    ["Bounded raw replay", "Same logical case with equivalent admission"],
], colLabels=["Check", "Observed result"], cellLoc="left", colWidths=[.36,.64], bbox=[0,.13,1,.8])
table.auto_set_font_size(False); table.set_fontsize(11)
for (r,c), cell in table.get_celld().items():
    cell.set_edgecolor("#d6dde3")
    if r == 0:
        cell.set_facecolor("#183c52"); cell.set_text_props(color="white", weight="bold")
    else:
        cell.set_facecolor("#eef4f6" if r % 2 else "white")
fig.text(.08, .04, "One broker / source partition · parallelism 1 · task recovery, not broker or node failure", fontsize=10, color="#435360")
fig.savefig(args.output / "part-1-recovery-results.png", dpi=200, bbox_inches="tight")
fig.savefig(args.output / "part-1-recovery-results.svg", bbox_inches="tight")
plt.close(fig)

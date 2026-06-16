"""Seed a batch of one-time jobs with varied resource demand, so you can watch the
scheduler place them across nodes by capacity.

Requires the API (main.py) to be running, since that process also ticks jobs into
runs. Example:

    python demo_seed.py --jobs 30 --tenants 2
"""

from __future__ import annotations

import argparse
import json
import random
import urllib.request


def submit(url: str, body: dict) -> None:
    data = json.dumps(body).encode()
    req = urllib.request.Request(
        f"{url}/jobs", data=data, headers={"Content-Type": "application/json"}
    )
    with urllib.request.urlopen(req, timeout=10) as resp:
        resp.read()


def main() -> None:
    parser = argparse.ArgumentParser(description="Seed demo jobs")
    parser.add_argument("--url", default="http://127.0.0.1:8000")
    parser.add_argument("--jobs", type=int, default=30)
    parser.add_argument("--tenants", type=int, default=1)
    args = parser.parse_args()

    for i in range(args.jobs):
        req_cpu = random.choice([1, 1, 1, 2, 3])  # mostly small, a few large
        submit(
            args.url,
            {
                "name": f"demo-{i}",
                "tenant_id": f"tenant-{random.randrange(args.tenants)}",
                "priority": random.randrange(10),
                "req_cpu": req_cpu,
                "req_mem_mb": req_cpu * 256,
                "payload": {"type": "sleep", "seconds": random.randint(3, 8)},
                "schedule": {"kind": "one_time"},
            },
        )
    print(f"submitted {args.jobs} jobs to {args.url}")
    print("watch placement:  curl -s {0}/nodes | python -m json.tool".format(args.url))


if __name__ == "__main__":
    main()

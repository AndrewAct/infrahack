#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.12,<3.14"
# dependencies = [
#   "pyiceberg[pyarrow,sql-sqlite]==0.10.0",
#   "pyarrow==21.0.0",
#   "boto3>=1.40,<2",
# ]
# ///
"""Run the Iceberg small-file experiment against real Amazon S3 from AWS.

Run cloud/run_in_cloudshell.sh in AWS CloudShell; see cloud/README.md.
Requires an existing dedicated S3 bucket. Creates a unique prefix for each run.
Does not delete objects or run snapshot expiration/orphan cleanup.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
import platform
import statistics
import sys
from datetime import UTC, datetime
from pathlib import Path
from time import perf_counter
from urllib.parse import unquote, urlparse

import boto3
import pyarrow as pa
import pyarrow.compute as pc
import pyiceberg
from botocore.exceptions import ClientError
from pyiceberg.catalog.sql import SqlCatalog
from pyiceberg.manifest import DATA_FILE_TYPE, DataFile
from pyiceberg.partitioning import PartitionField, PartitionSpec
from pyiceberg.schema import Schema
from pyiceberg.table import Table
from pyiceberg.transforms import IdentityTransform
from pyiceberg.types import LongType, NestedField, StringType


def s3_parts(uri: str) -> tuple[str, str]:
    parsed = urlparse(uri)
    if parsed.scheme != "s3" or not parsed.netloc or parsed.query or parsed.fragment:
        raise ValueError("Expected s3://bucket/prefix without a query or fragment")
    return parsed.netloc, unquote(parsed.path).lstrip("/")


def object_summary(client, uri: str) -> dict:
    bucket, prefix = s3_parts(uri)
    pages = client.get_paginator("list_objects_v2").paginate(
        Bucket=bucket, Prefix=prefix
    )
    objects = [obj for page in pages for obj in page.get("Contents", [])]
    return {"objects": len(objects), "bytes": sum(obj["Size"] for obj in objects)}


def object_exists(client, uri: str) -> bool:
    bucket, key = s3_parts(uri)
    try:
        client.head_object(Bucket=bucket, Key=key)
        return True
    except ClientError as error:
        if error.response["Error"]["Code"] in {"404", "NoSuchKey", "NotFound"}:
            return False
        raise


def measure_ms(action, repeats: int) -> float:
    values = []
    for _ in range(repeats):
        start = perf_counter()
        action()
        values.append((perf_counter() - start) * 1000)
    return statistics.median(values)


def make_data(rows: int) -> pa.Table:
    ids = list(range(rows))
    return pa.table(
        {
            "event_id": pa.array(ids, type=pa.int64()),
            "region": pa.array([("US", "CA", "GB", "DE")[i % 4] for i in ids]),
            "amount_cents": pa.array(
                [(i * 7919) % 100000 for i in ids], type=pa.int64()
            ),
            "event_type": pa.array([("view", "purchase", "click")[i % 3] for i in ids]),
        }
    )


def validate(table: Table, expected: pa.Table) -> None:
    actual = table.scan().to_arrow().sort_by("event_id")
    if not actual.equals(expected):
        raise AssertionError(f"Row contents differ for {table.name()}")


def describe(table: Table, name: str, repeats: int, client, catalog) -> dict:
    snapshot = table.current_snapshot()
    if snapshot is None:
        raise AssertionError("Expected a committed snapshot")
    files = [task.file for task in table.scan().plan_files()]
    sizes = [f.file_size_in_bytes for f in files]
    manifests = snapshot.manifests(table.io)
    manifest_list_bytes = len(table.io.new_input(snapshot.manifest_list))
    metadata_dir = table.metadata_location.rsplit("/", 1)[0] + "/"
    return {
        "case": name,
        "rows": sum(f.record_count for f in files),
        "snapshots": len(table.snapshots()),
        "live_files": len(files),
        "live_manifests": len(manifests),
        "data_bytes": sum(sizes),
        "mean_file_bytes": round(statistics.mean(sizes)),
        "latest_metadata_json_bytes": len(table.io.new_input(table.metadata_location)),
        "current_manifest_list_bytes": manifest_list_bytes,
        "all_metadata_directory_bytes": object_summary(client, metadata_dir)["bytes"],
        "catalog_load_median_ms": round(
            measure_ms(lambda: catalog.load_table(table.name()), repeats), 3
        ),
        "plan_files_median_ms": round(
            measure_ms(lambda: list(table.scan().plan_files()), repeats), 3
        ),
        "projected_read_median_ms": round(
            measure_ms(
                lambda: table.scan(
                    selected_fields=("event_id", "amount_cents")
                ).to_arrow(),
                repeats,
            ),
            3,
        ),
        "metadata_location": table.metadata_location,
    }


def markdown_report(results: list[dict]) -> str:
    lines = [
        "| Case | Snapshots | Live files | Live manifests | Mean file KiB | Latest JSON KiB | Manifest list KiB | Plan ms | Read ms |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for r in results:
        lines.append(
            f"| {r['case']} | {r['snapshots']} | {r['live_files']} | {r['live_manifests']} "
            f"| {r['mean_file_bytes'] / 1024:.2f} | {r['latest_metadata_json_bytes'] / 1024:.2f} "
            f"| {r['current_manifest_list_bytes'] / 1024:.2f} | {r['plan_files_median_ms']:.3f} "
            f"| {r['projected_read_median_ms']:.3f} |"
        )
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--bucket", required=True, help="Dedicated existing S3 lab bucket"
    )
    parser.add_argument("--region", default="us-east-1")
    parser.add_argument("--rows", type=int, default=120000)
    parser.add_argument("--small-batch-rows", type=int, default=1000)
    parser.add_argument("--large-batch-rows", type=int, default=10000)
    parser.add_argument("--repeats", type=int, default=5)
    parser.add_argument(
        "--output", type=Path, default=Path(__file__).resolve().parent / "results"
    )
    args = parser.parse_args()
    if min(args.rows, args.small_batch_rows, args.large_batch_rows, args.repeats) <= 0:
        parser.error("Row counts and repeats must be positive")
    if args.rows > 1200000 or args.repeats > 5:
        parser.error("Lab limits: at most 1,200,000 rows and 5 repeats")
    if (
        math.ceil(args.rows / args.small_batch_rows) > 120
        or math.ceil(args.rows / args.large_batch_rows) > 120
    ):
        parser.error("Lab limits: at most 120 append calls in either batch case")
    if not args.bucket.startswith("iceberg-small-files-lab-") or "/" in args.bucket:
        parser.error(
            "Use a dedicated bucket named iceberg-small-files-lab-<unique suffix>"
        )
    if os.environ.get("AWS_REGION") != args.region:
        parser.error(
            "Run from AWS CloudShell in the selected region (AWS_REGION must match)"
        )

    session = boto3.Session(region_name=args.region)
    credentials = session.get_credentials()
    if credentials is None:
        parser.error(
            "No AWS session credentials. Sign in to AWS CloudShell; do not paste keys into the script"
        )
    frozen = credentials.get_frozen_credentials()
    client = session.client("s3")
    location = client.get_bucket_location(Bucket=args.bucket).get("LocationConstraint")
    bucket_region = {None: "us-east-1", "EU": "eu-west-1"}.get(location, location)
    if bucket_region != args.region:
        parser.error("Bucket and CloudShell must use the same AWS region")
    tags = client.get_bucket_tagging(Bucket=args.bucket)["TagSet"]
    if {tag["Key"]: tag["Value"] for tag in tags}.get(
        "purpose"
    ) != "iceberg-small-files-lab":
        parser.error("The bucket must have purpose=iceberg-small-files-lab tag")

    args.output.mkdir(parents=True, exist_ok=True)
    root = args.output.resolve() / datetime.now(UTC).strftime("run-%Y%m%dT%H%M%S%fZ")
    root.mkdir(exist_ok=False)
    remote_root = f"s3://{args.bucket}/runs/{root.name}"
    warehouse = remote_root + "/warehouse"
    catalog = SqlCatalog(
        "aws_s3_experiment",
        uri=f"sqlite:///{root / 'catalog.db'}",
        warehouse=warehouse,
        **{
            "py-io-impl": "pyiceberg.io.pyarrow.PyArrowFileIO",
            "s3.region": args.region,
            "s3.access-key-id": frozen.access_key,
            "s3.secret-access-key": frozen.secret_key,
            "s3.session-token": frozen.token or "",
        },
    )
    catalog.create_namespace("demo")
    data = make_data(args.rows)
    schema = Schema(
        NestedField(1, "event_id", LongType(), required=False),
        NestedField(2, "region", StringType(), required=False),
        NestedField(3, "amount_cents", LongType(), required=False),
        NestedField(4, "event_type", StringType(), required=False),
    )
    partition_spec = PartitionSpec(
        PartitionField(
            source_id=2, field_id=1000, transform=IdentityTransform(), name="region"
        )
    )

    def create_table(name: str) -> Table:
        return catalog.create_table(
            f"demo.{name}",
            schema=schema,
            partition_spec=partition_spec,
            properties={
                "format-version": "2",
                "write.target-file-size-bytes": str(512 * 1024 * 1024),
                "write.parquet.compression-codec": "zstd",
                "commit.manifest-merge.enabled": "false",
            },
        )

    results = []
    tables = {}
    for name, batch_rows in (
        ("frequent_appends", args.small_batch_rows),
        ("larger_batches", args.large_batch_rows),
        ("one_append", args.rows),
    ):
        table = create_table(name)
        start = perf_counter()
        for offset in range(0, args.rows, batch_rows):
            table.append(data.slice(offset, batch_rows))
        elapsed = perf_counter() - start
        validate(table, data)
        record = describe(table, name, args.repeats, client, catalog)
        record["append_total_seconds"] = round(elapsed, 6)
        results.append(record)
        tables[name] = table
        print(
            f"{name}: {record['snapshots']} snapshots, {record['live_files']} files",
            flush=True,
        )

    # Separate copies keep every table's file ownership independent during maintenance.
    frequent = tables["frequent_appends"]
    source_descriptors = [task.file for task in frequent.scan().plan_files()]
    source_files = [f.file_path for f in source_descriptors]
    imported = create_table("one_commit_existing_small_files")
    import_dir = imported.location().rstrip("/") + "/imported-data"
    copied_descriptors = []
    for index, source in enumerate(source_descriptors):
        target = import_dir + f"/part-{index:06d}.parquet"
        source_bucket, source_key = s3_parts(source.file_path)
        target_bucket, target_key = s3_parts(target)
        client.copy_object(
            Bucket=target_bucket,
            Key=target_key,
            CopySource={"Bucket": source_bucket, "Key": source_key},
        )
        attributes = {
            field.name: getattr(source, field.name)
            for field in DATA_FILE_TYPE[2].fields
        }
        attributes["file_path"] = target
        descriptor = DataFile.from_args(**attributes)
        descriptor.spec_id = source.spec_id
        copied_descriptors.append(descriptor)
    # These files already carry matching Iceberg field IDs and partition metadata.
    with imported.transaction() as transaction:
        with transaction.update_snapshot().fast_append() as append:
            for descriptor in copied_descriptors:
                append.append_data_file(descriptor)
    validate(imported, data)
    results.append(
        describe(
            imported, "one_commit_existing_small_files", args.repeats, client, catalog
        )
    )

    # Bounded in-memory replacement, not a distributed compaction implementation.
    before_snapshot_id = frequent.current_snapshot().snapshot_id
    frequent.overwrite(data)
    validate(frequent, data)
    old = frequent.scan(snapshot_id=before_snapshot_id).to_arrow().sort_by("event_id")
    if not old.equals(data):
        raise AssertionError("Previous snapshot must remain readable after rewrite")
    retained = sum(object_exists(client, p) for p in source_files)
    if retained != len(source_files):
        raise AssertionError(
            "Old files unexpectedly disappeared before snapshot expiration"
        )
    rewritten = describe(
        frequent, "frequent_after_full_rewrite", args.repeats, client, catalog
    )
    rewritten["old_data_files_still_present"] = retained
    rewritten["previous_snapshot_verified"] = before_snapshot_id
    results.append(rewritten)

    # An independent aggregate check in addition to full record equality above.
    expected_sum = pc.sum(data["amount_cents"]).as_py()
    for table in [*tables.values(), imported]:
        if (
            pc.sum(
                table.scan(selected_fields=("amount_cents",)).to_arrow()["amount_cents"]
            ).as_py()
            != expected_sum
        ):
            raise AssertionError("Aggregate changed")

    payload = {
        "environment": {
            "python": sys.version,
            "platform": platform.platform(),
            "machine": platform.machine(),
            "pyiceberg": pyiceberg.__version__,
            "pyarrow": pa.__version__,
            "boto3": boto3.__version__,
            "aws_region": args.region,
            "execution_env": os.environ.get("AWS_EXECUTION_ENV", "unspecified"),
            "storage": "Amazon S3 Standard",
            "catalog": "SQLite inside the cloud compute environment",
            "script_sha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        },
        "parameters": {
            k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()
        },
        "method": "AWS compute with data files, manifests, manifest lists and table JSON in same-region S3. SQLite catalog is local to the AWS compute environment. Iceberg v2, four identity partitions, fast append (manifest merge disabled). Repeated planning/reads after validation; no cache clearing. Catalog-load timing includes local SQLite and remote metadata JSON; planning starts from a loaded table. No Spark/Trino or distributed catalog contention. No GET/PUT request metering or production speedup claim.",
        "s3_warehouse_summary": object_summary(client, warehouse + "/"),
        "remote_run": remote_root,
        "validation": {
            "full_row_equality": True,
            "aggregate_equality": True,
            "previous_snapshot_readable": True,
            "old_objects_retained": retained == len(source_files),
        },
        "results": results,
    }
    (root / "results.json").write_text(json.dumps(payload, indent=2) + "\n")
    report = markdown_report(results)
    (root / "results.md").write_text(report)
    # The database contains catalog state, not the in-memory session credentials.
    catalog.engine.dispose()
    artifacts = (
        root / "results.json",
        root / "results.md",
        root / "catalog.db",
        Path(__file__),
        Path(__file__).with_suffix(".py.lock"),
    )
    for artifact in artifacts:
        contents = artifact.read_bytes()
        if any(
            value and value.encode() in contents
            for value in (frozen.access_key, frozen.secret_key, frozen.token)
        ):
            raise RuntimeError(
                "Refusing to archive an artifact containing session credentials"
            )
    for artifact in artifacts:
        client.upload_file(
            str(artifact), args.bucket, f"runs/{root.name}/artifacts/{artifact.name}"
        )
    print(report)
    print(f"Old files retained after rewrite: {retained}/{len(source_files)}")
    print(f"Results: {root}")
    print(f"S3 archive: {remote_root}/artifacts/")


if __name__ == "__main__":
    main()

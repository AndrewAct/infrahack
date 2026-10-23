# AWS CloudShell + S3 experiment

This setup runs the compute in AWS CloudShell and stores all Iceberg data files, manifests, manifest lists, and metadata JSON in S3 Standard in `us-east-1`. A local SQLite catalog in CloudShell avoids provisioning a shared catalog service.

CloudShell is a convenient, resource-limited client. This experiment demonstrates Iceberg file and metadata behavior on object storage; it is not a calibrated distributed Spark or Trino benchmark.

## Cost and retention

AWS CloudShell has no separate compute charge. S3 storage and requests are billed to the account. The original planning envelope for the default workload was approximately $0.25 per run, but AWS pricing can change and this is not a hard spending cap. Repeated runs create additional requests and objects.

The runner applies a seven-day expiration policy to objects under `runs/`. Download any results you want to keep before they expire. The empty bucket remains until it is removed separately; the script does not delete buckets or existing objects.

## Prerequisites

- An AWS account with CloudShell access in `us-east-1`.
- Permission for `sts:GetCallerIdentity`.
- Permission to create an S3 bucket; configure its public-access block, encryption, tags, and lifecycle; inspect its location and tags; list it; and read, write, and copy objects.
- No static access keys are required. The script uses credentials from the signed-in CloudShell session.

## Run

Upload or clone the repository into AWS CloudShell, then run from the repository root:

```bash
bash cloud/run_in_cloudshell.sh
```

The runner:

1. Verifies that CloudShell is using `us-east-1`.
2. Installs the pinned `uv` release in a temporary directory when needed.
3. Creates a uniquely named private lab bucket.
4. Enables public-access blocking and server-side encryption.
5. Applies the required lab tag and a seven-day lifecycle rule.
6. Runs the default experiment once with a 15-minute timeout.

The generated bucket name is recorded in `cloud/lab-state.env`. The latest console output is recorded in `cloud/last-run.log`. Both files are ignored by Git.

To use an already prepared bucket, verify that it is in the same region and tagged `purpose=iceberg-small-files-lab`, then run:

```bash
uv run --locked --python 3.12 small_file_experiment.py \
  --bucket iceberg-small-files-lab-YOUR-SUFFIX \
  --region us-east-1 \
  --rows 120000 \
  --repeats 5 \
  --output cloud-results
```

## Output

Each run writes to a new timestamped local directory and a matching S3 prefix. On success, it produces `results.json`, `results.md`, and `catalog.db`, then uploads those artifacts together with the script and lockfile under `s3://BUCKET/runs/RUN-ID/artifacts/`.

The comparisons cover frequent appends, larger batches, one large append, one commit of physically copied small files, and replacement of the frequent-append table. The script checks full row equality, an independent aggregate, old-object existence, and old-snapshot contents.

Download the successful run directory before the lifecycle policy expires its S3 copy. Do not commit generated output or credentials.

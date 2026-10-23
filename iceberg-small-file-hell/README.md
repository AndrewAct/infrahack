# Iceberg Small-File Hell

A small, reproducible experiment that demonstrates how frequent Iceberg appends create many data and metadata files, and compares that pattern with larger batches, a single append, and a rewrite.

The experiment runs in AWS CloudShell and stores the Iceberg warehouse in a dedicated Amazon S3 bucket. It uses a local SQLite catalog inside CloudShell so that the experiment stays focused on Iceberg's file layout rather than on provisioning a shared catalog or distributed query engine.

## Repository contents

| Path | Purpose |
| --- | --- |
| `small_file_experiment.py` | Main S3-backed experiment and report generator |
| `small_file_experiment.py.lock` | Locked Python dependency resolution for `uv` |
| `cloud/run_in_cloudshell.sh` | Creates and configures a private lab bucket, then runs the experiment |
| `cloud/test_storage_helpers.py` | Credential-free unit tests for S3 URI and object helper functions |
| `cloud/README.md` | AWS prerequisites, cost notes, and exact run steps |
| `INSTRUCTIONS.md` | Development and safety instructions for maintainers and coding agents |

Generated benchmark data, article drafts, research notes, images, caches, and archived versions are intentionally not included.

## Quick start

1. Open AWS CloudShell in `us-east-1`.
2. Upload or clone this repository into CloudShell.
3. From the repository root, run:

```bash
bash cloud/run_in_cloudshell.sh
```

The runner installs the pinned `uv` release if necessary, creates a uniquely named private S3 bucket, applies encryption, public-access blocking, tags, and a seven-day lifecycle rule, and then runs the experiment once with a 15-minute timeout.

For prerequisites, expected cost, outputs, and cleanup considerations, see [cloud/README.md](cloud/README.md).

## Local checks

These checks do not run the benchmark or access S3:

```bash
uv run --locked --python 3.12 small_file_experiment.py --help
python3 -m py_compile small_file_experiment.py cloud/test_storage_helpers.py
```

The unit tests import the experiment's third-party dependencies, so run them from an environment containing the dependencies pinned in `small_file_experiment.py.lock`:

```bash
python3 -m unittest discover -s cloud -p 'test_*.py'
```

## Safety

The experiment accepts only dedicated bucket names beginning with `iceberg-small-files-lab-` and requires the bucket tag `purpose=iceberg-small-files-lab`. It writes synthetic data under a unique `runs/` prefix. It does not delete buckets or objects, expire Iceberg snapshots, or remove orphan files.

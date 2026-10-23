# Project Instructions

These instructions apply to maintainers and coding agents working in this repository.

## Scope

- Keep the repository focused on the executable Iceberg small-file experiment.
- Do not add article drafts, cover images, research notes, benchmark output, local warehouses, caches, or archived implementations.
- Treat `small_file_experiment.py.lock` as the reproducibility source of truth. Update it whenever dependencies change.

## Development workflow

1. Make the smallest change that satisfies the task.
2. Keep the default experiment bounded: at most 1,200,000 rows, five timing repeats, and 120 append calls per batch case.
3. Run syntax and CLI checks locally before committing.
4. Run the storage-helper unit tests in an environment containing the locked dependencies.
5. Only run the full experiment from an authenticated AWS CloudShell session in the selected region.

## AWS safety constraints

- Never commit credentials, session tokens, account identifiers, bucket state, logs, catalogs, or generated result files.
- Use only a dedicated bucket named `iceberg-small-files-lab-<unique suffix>` and tagged `purpose=iceberg-small-files-lab`.
- Keep S3 public-access blocking and server-side encryption enabled.
- Preserve the seven-day lifecycle rule for objects under `runs/` unless a user explicitly requests a different retention policy.
- Do not add automatic retries: each retry creates more S3 requests and objects.
- Do not add bucket deletion, recursive object deletion, snapshot expiration, or orphan-file cleanup without explicit user approval and dedicated validation.

## Validation

At minimum, run:

```bash
uv run --locked --python 3.12 small_file_experiment.py --help
python3 -m py_compile small_file_experiment.py cloud/test_storage_helpers.py
```

When the locked dependencies are installed, also run:

```bash
python3 -m unittest discover -s cloud -p 'test_*.py'
```

Do not present a CLI or unit-test check as a completed S3 benchmark. A benchmark is complete only when the full CloudShell run succeeds and emits its result artifacts.

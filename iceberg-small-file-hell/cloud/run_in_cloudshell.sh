#!/usr/bin/env bash
# Run inside AWS CloudShell in us-east-1. Creates a fresh synthetic-data lab only.
set -euo pipefail
export AWS_PAGER=""

lab_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cd "$lab_root"
if [[ "${AWS_REGION:-}" != "us-east-1" ]]; then
  echo 'Open AWS CloudShell in us-east-1 before running this script.' >&2
  exit 2
fi
for lab_tool in aws curl python3 timeout; do
  command -v "$lab_tool" >/dev/null || { echo "Missing tool: $lab_tool" >&2; exit 2; }
done
aws sts get-caller-identity --query Account --output text >/dev/null

lab_tmp=$(mktemp -d /tmp/iceberg-cloud.XXXXXX)
export UV_CACHE_DIR="$lab_tmp/uv-cache"
export UV_PYTHON_INSTALL_DIR="$lab_tmp/python"
if command -v uv >/dev/null; then
  lab_uv=$(command -v uv)
else
  curl --proto '=https' --tlsv1.2 -fsSL https://astral.sh/uv/0.11.26/install.sh -o "$lab_tmp/install-uv.sh"
  UV_INSTALL_DIR="$lab_tmp/bin" UV_NO_MODIFY_PATH=1 sh "$lab_tmp/install-uv.sh"
  lab_uv="$lab_tmp/bin/uv"
fi

# Resolve runtime before creating billable storage; this does not run the benchmark.
"$lab_uv" run --locked --python 3.12 small_file_experiment.py --help >/dev/null

lab_suffix=$(python3 -c 'import uuid; print(uuid.uuid4().hex[:16])')
lab_bucket="iceberg-small-files-lab-$lab_suffix"
echo "Creating dedicated lab bucket: $lab_bucket (us-east-1)"
echo 'Only synthetic lab data will be written. All runs/ objects expire after 7 days.'
echo 'Planning estimate: below $0.25 at the documented envelope; this is not a billing cap.'
aws s3api create-bucket --bucket "$lab_bucket" --region us-east-1 >/dev/null
# Keep the public bucket name immediately, including if a later setup step fails.
printf 'LAB_BUCKET=%s\nLAB_REGION=us-east-1\n' "$lab_bucket" > "$lab_root/cloud/lab-state.env"
aws s3api put-public-access-block --bucket "$lab_bucket" --public-access-block-configuration \
  BlockPublicAcls=true,IgnorePublicAcls=true,BlockPublicPolicy=true,RestrictPublicBuckets=true
aws s3api put-bucket-encryption --bucket "$lab_bucket" --server-side-encryption-configuration \
  '{"Rules":[{"ApplyServerSideEncryptionByDefault":{"SSEAlgorithm":"AES256"}}]}'
aws s3api put-bucket-tagging --bucket "$lab_bucket" --tagging \
  '{"TagSet":[{"Key":"purpose","Value":"iceberg-small-files-lab"}]}'
aws s3api put-bucket-lifecycle-configuration --bucket "$lab_bucket" --lifecycle-configuration \
  '{"Rules":[{"ID":"expire-synthetic-lab-after-7-days","Status":"Enabled","Filter":{"Prefix":"runs/"},"Expiration":{"Days":7},"AbortIncompleteMultipartUpload":{"DaysAfterInitiation":1}}]}'

# One process, fixed workload, no job scheduling and no automatic retry of the run.
set +e
timeout --signal=TERM --kill-after=30s 900s \
  "$lab_uv" run --locked --python 3.12 small_file_experiment.py \
  --bucket "$lab_bucket" --region us-east-1 --rows 120000 --repeats 5 \
  --output "$lab_root/cloud-results" 2>&1 | tee "$lab_root/cloud/last-run.log"
lab_status=${PIPESTATUS[0]}
set -e
if [[ "$lab_status" -ne 0 ]]; then
  echo "Experiment stopped (exit $lab_status). No run is automatically retried." >&2
  echo "Bucket: $lab_bucket; partial lab objects expire under the 7-day lifecycle." >&2
  exit "$lab_status"
fi
echo 'Cloud run completed. Download cloud-results/ before the S3 archive expires.'
echo "Bucket: $lab_bucket; see cloud/lab-state.env and cloud/last-run.log."

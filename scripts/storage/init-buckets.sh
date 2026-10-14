#!/bin/sh
# Creates the two local buckets in RustFS (docker-compose's storage-init runs
# this once on `docker compose up`). Safe to run again. Production buckets are
# created in the provider's dashboard instead — see AGENTS.md, "File storage".
set -e
S3="aws --endpoint-url ${STORAGE_ENDPOINT} s3api"

for bucket in "${STORAGE_PUBLIC_BUCKET}" "${STORAGE_PRIVATE_BUCKET}"; do
  $S3 head-bucket --bucket "$bucket" 2>/dev/null || $S3 create-bucket --bucket "$bucket"
  # Browsers upload straight to storage, so the frontend's origin must be allowed.
  $S3 put-bucket-cors --bucket "$bucket" --cors-configuration "{\"CORSRules\":[{
    \"AllowedOrigins\":[\"${FRONTEND_ORIGIN}\"],\"AllowedMethods\":[\"PUT\",\"GET\",\"HEAD\"],
    \"AllowedHeaders\":[\"*\"],\"ExposeHeaders\":[\"ETag\"],\"MaxAgeSeconds\":3600}]}"
done

# Only the PUBLIC bucket is readable by anyone. The private one has no policy,
# so every read needs a signed link.
$S3 put-bucket-policy --bucket "${STORAGE_PUBLIC_BUCKET}" --policy "{\"Version\":\"2012-10-17\",
  \"Statement\":[{\"Effect\":\"Allow\",\"Principal\":{\"AWS\":[\"*\"]},\"Action\":[\"s3:GetObject\"],
  \"Resource\":[\"arn:aws:s3:::${STORAGE_PUBLIC_BUCKET}/*\"]}]}"

echo "Buckets ready: ${STORAGE_PUBLIC_BUCKET} (public read), ${STORAGE_PRIVATE_BUCKET} (private)"

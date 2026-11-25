#!/usr/bin/env bash
# Uploads any APK as a GitHub Release asset using a fine-grained PAT.
# Works from Termux, PC, anywhere curl exists. Bypasses the 25MB/100MB
# git/web limits entirely (GitHub Releases allow up to 2GB per file).
#
# Usage:
#   export GH_PAT="github_pat_xxx..."
#   ./upload_apk_release.sh <owner>/<repo> /path/to/app.apk
set -euo pipefail

REPO="$1"            # e.g. tanzirdev/DeDex
APK="$2"             # e.g. /sdcard/Download/some.apk
TAG="apk-$(date +%Y%m%d-%H%M%S)"
NAME="$(basename "$APK")"

if [ -z "${GH_PAT:-}" ]; then
  echo "Set GH_PAT first: export GH_PAT=github_pat_xxxxx"
  exit 1
fi

echo "== Creating release $TAG on $REPO"
REL_JSON="$(curl -sS -X POST \
  -H "Authorization: Bearer $GH_PAT" \
  -H "Accept: application/vnd.github+json" \
  "https://api.github.com/repos/$REPO/releases" \
  -d "{\"tag_name\":\"$TAG\",\"name\":\"$NAME\",\"body\":\"Uploaded for DeDex memory-dump analysis\"}")"

REL_ID="$(echo "$REL_JSON" | sed -n 's/.*"id": *\([0-9]*\),.*/\1/p' | head -1)"
if [ -z "$REL_ID" ]; then
  echo "Failed to create release. Response:"
  echo "$REL_JSON"
  exit 1
fi
echo "== Release id: $REL_ID"

echo "== Uploading $NAME ($(du -h "$APK" | cut -f1))"
UP_JSON="$(curl -sS -X POST \
  -H "Authorization: Bearer $GH_PAT" \
  -H "Content-Type: application/vnd.android.package-archive" \
  --data-binary @"$APK" \
  "https://uploads.github.com/repos/$REPO/releases/$REL_ID/assets?name=$NAME")"

URL="$(echo "$UP_JSON" | sed -n 's/.*"browser_download_url": *"\([^"]*\)".*/\1/p' | head -1)"
if [ -z "$URL" ]; then
  echo "Upload may have failed. Response:"
  echo "$UP_JSON"
  exit 1
fi

echo
echo "== Done. Use this as the 'apk_url' input when you run the workflow:"
echo "$URL"

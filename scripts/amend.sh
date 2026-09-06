#!/usr/bin/env bash
set -euo pipefail

# Amends a paused or completed run's requirement: ./scripts/amend.sh <runId> --requirement <file>
#
# Re-plans from REQUIREMENT (the changed node): every downstream node currently COMPLETED
# is archived, checkpoint-restored, and returned to PENDING; its evidence is revoked; a
# prior approval stops counting once state.json reflects the new requirement revision
# (spec 05's own revision-keyed approval check does that automatically on the next
# ./scripts/approve.sh or ./scripts/resume.sh, no separate step here). Writes the re-planned
# state back to runs/<runId>/state.json; run ./scripts/resume.sh <runId> afterward to
# re-execute the invalidated nodes.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <runId> --requirement <file> [--workflow <path>] [--target-service <path>]" >&2
  exit 2
fi

RUN_ID="$1"
shift

"$ROOT_DIR/scripts/build.sh" >&2

if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

"$JAVA" -cp "$ROOT_DIR/orchestrator/out/main" com.schwab.agentic.cli.Main amend \
  --run-id "$RUN_ID" \
  --workflow "$ROOT_DIR/workflows/approval-demo.json" \
  --runs "$ROOT_DIR/runs" \
  --fixtures "$ROOT_DIR/fixtures" \
  "$@"

#!/usr/bin/env bash
set -euo pipefail

# Records a human approval: ./scripts/approve.sh <runId> <nodeId> --by "name" --reason "..."
#
# Writes the decision to runs/<runId>/approvals.json, keyed to the run's current
# requirement revision, and moves the node from WAITING_APPROVAL back to PENDING so the
# next ./scripts/resume.sh picks it up.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -lt 2 ]]; then
  echo "Usage: $0 <runId> <nodeId> --by \"name\" --reason \"...\"" >&2
  exit 2
fi

RUN_ID="$1"
NODE_ID="$2"
shift 2

"$ROOT_DIR/scripts/build.sh" >&2

if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

"$JAVA" -cp "$ROOT_DIR/orchestrator/out/main" com.schwab.agentic.cli.Main approve \
  --run-id "$RUN_ID" "$NODE_ID" \
  --workflow "$ROOT_DIR/workflows/sdlc-default.json" \
  --runs "$ROOT_DIR/runs" \
  "$@"

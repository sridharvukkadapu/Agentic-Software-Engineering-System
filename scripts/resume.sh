#!/usr/bin/env bash
set -euo pipefail

# Resumes a paused run: ./scripts/resume.sh <runId> [--live | --replay]
#
# Loads runs/<runId>/state.json and continues the scheduling loop from where it stopped,
# in this (freshly started) process, regardless of which process originally ran it.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <runId> [--live | --replay]" >&2
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

"$JAVA" -cp "$ROOT_DIR/orchestrator/out/main" com.schwab.agentic.cli.Main resume \
  --run-id "$RUN_ID" \
  --workflow "$ROOT_DIR/workflows/approval-demo.json" \
  --runs "$ROOT_DIR/runs" \
  --fixtures "$ROOT_DIR/fixtures" \
  "$@"

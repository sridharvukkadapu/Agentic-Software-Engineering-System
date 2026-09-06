#!/usr/bin/env bash
set -euo pipefail

# Renders a run's report: ./scripts/report.sh <runId> [--out <path>] [--runs <dir>]
#
# Reads runs/<runId>/state.json and recomputes every metric from that run's persisted
# audit log, so this works against any finished run, including the committed demo runs,
# without needing the process that produced it. Writes runs/<runId>/report.md by default
# and prints the report to stdout.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <runId> [--out <path>] [--runs <dir>]" >&2
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

"$JAVA" -cp "$ROOT_DIR/orchestrator/out/main" com.schwab.agentic.cli.Main report \
  --run-id "$RUN_ID" \
  --runs "$ROOT_DIR/runs" \
  "$@"

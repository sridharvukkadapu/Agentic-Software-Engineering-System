#!/usr/bin/env bash
set -euo pipefail

# Starts a run: ./scripts/run.sh <scenario> [--live | --replay] [--auto-approve] [--run-id <id>]
#
# <scenario> names a directory under scenarios/<scenario>/requirement.md. Builds the
# orchestrator first (via build.sh) so a stale ./out is never run against.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

if [[ $# -lt 1 ]]; then
  echo "Usage: $0 <scenario> [--live | --replay] [--auto-approve] [--run-id <id>]" >&2
  exit 2
fi

SCENARIO="$1"
shift

REQUIREMENT_PATH="$ROOT_DIR/scenarios/$SCENARIO/requirement.md"
if [[ ! -f "$REQUIREMENT_PATH" ]]; then
  echo "error: no requirement.md found for scenario \"$SCENARIO\" at $REQUIREMENT_PATH" >&2
  exit 1
fi

"$ROOT_DIR/scripts/build.sh" >&2

if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA="java"
fi

"$JAVA" -cp "$ROOT_DIR/orchestrator/out/main" com.schwab.agentic.cli.Main run \
  --workflow "$ROOT_DIR/workflows/sdlc-default.json" \
  --requirement "$REQUIREMENT_PATH" \
  --runs "$ROOT_DIR/runs" \
  --fixtures "$ROOT_DIR/fixtures" \
  "$@"

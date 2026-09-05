#!/usr/bin/env bash
set -euo pipefail

# Builds the orchestrator with javac only. No Maven, no Gradle, no network.
#
# JAVA_HOME is resolved if set, otherwise javac on PATH is used. A hardcoded JDK path
# fails on the first command a reviewer runs on a machine laid out differently from the
# one this was written on, so neither path is ever assumed.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORCHESTRATOR_DIR="$ROOT_DIR/orchestrator"
OUT_DIR="$ORCHESTRATOR_DIR/out"

if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVAC="$JAVA_HOME/bin/javac"
else
  JAVAC="javac"
fi

if ! command -v "$JAVAC" >/dev/null 2>&1; then
  echo "error: javac not found. Set JAVA_HOME to a Java 21+ installation, or put javac on PATH." >&2
  exit 1
fi

JAVAC_VERSION_OUTPUT="$("$JAVAC" -version 2>&1)"
JAVAC_MAJOR_VERSION="$(echo "$JAVAC_VERSION_OUTPUT" | sed -E 's/^javac ([0-9]+).*/\1/')"

if ! [[ "$JAVAC_MAJOR_VERSION" =~ ^[0-9]+$ ]]; then
  echo "error: could not determine javac version from output: $JAVAC_VERSION_OUTPUT" >&2
  exit 1
fi

if [[ "$JAVAC_MAJOR_VERSION" -lt 21 ]]; then
  echo "error: Java 21+ required, found $JAVAC_MAJOR_VERSION" >&2
  echo "Set JAVA_HOME to a Java 21+ installation." >&2
  exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/main"

MAIN_SOURCES="$(find "$ORCHESTRATOR_DIR/src/main/java" -name '*.java')"

if [[ -z "$MAIN_SOURCES" ]]; then
  echo "error: no source files found under $ORCHESTRATOR_DIR/src/main/java" >&2
  exit 1
fi

"$JAVAC" --release 21 -d "$OUT_DIR/main" $MAIN_SOURCES

echo "Build succeeded: $OUT_DIR/main"

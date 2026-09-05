#!/usr/bin/env bash
set -euo pipefail

# Compiles the orchestrator's main and test sources, then runs the hand-rolled TestRunner
# (no JUnit, zero dependencies) against every discovered test class. Exits non-zero if
# any test failed, so CI and a reviewer's terminal both see a real failure as a real
# failure, not a swallowed exception.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORCHESTRATOR_DIR="$ROOT_DIR/orchestrator"
OUT_DIR="$ORCHESTRATOR_DIR/out"

if [[ -n "${JAVA_HOME:-}" ]]; then
  JAVAC="$JAVA_HOME/bin/javac"
  JAVA="$JAVA_HOME/bin/java"
else
  JAVAC="javac"
  JAVA="java"
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
mkdir -p "$OUT_DIR/main" "$OUT_DIR/test"

MAIN_SOURCES="$(find "$ORCHESTRATOR_DIR/src/main/java" -name '*.java')"
TEST_SOURCES="$(find "$ORCHESTRATOR_DIR/src/test/java" -name '*.java')"

if [[ -z "$MAIN_SOURCES" ]]; then
  echo "error: no source files found under $ORCHESTRATOR_DIR/src/main/java" >&2
  exit 1
fi
if [[ -z "$TEST_SOURCES" ]]; then
  echo "error: no test files found under $ORCHESTRATOR_DIR/src/test/java" >&2
  exit 1
fi

"$JAVAC" --release 21 -d "$OUT_DIR/main" $MAIN_SOURCES
"$JAVAC" --release 21 -cp "$OUT_DIR/main" -d "$OUT_DIR/test" $TEST_SOURCES

# Every non-fixture, non-runner class under src/test whose name ends in "Test" is a test
# class discovered here and handed to TestRunner by fully-qualified name.
TEST_CLASSES=()
while IFS= read -r class_file; do
  relative_path="${class_file#"$ORCHESTRATOR_DIR"/src/test/java/}"
  class_name="${relative_path%.java}"
  class_name="${class_name//\//.}"
  if [[ "$class_name" == *Test ]]; then
    TEST_CLASSES+=("$class_name")
  fi
done < <(find "$ORCHESTRATOR_DIR/src/test/java" -name '*Test.java' | sort)

if [[ ${#TEST_CLASSES[@]} -eq 0 ]]; then
  echo "error: no test classes discovered under $ORCHESTRATOR_DIR/src/test/java" >&2
  exit 1
fi

"$JAVA" -cp "$OUT_DIR/main:$OUT_DIR/test" com.schwab.agentic.TestRunner "${TEST_CLASSES[@]}"

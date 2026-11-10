#!/usr/bin/env bash
# Fast, offline JVM-only checks for the pure exchange state machine.
# Requires JDK and a local kotlinc; NOT a replacement for Android tests.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
for cmd in javac java kotlinc; do
  if ! command -v "$cmd" >/dev/null 2>&1; then
    echo "Missing $cmd: install JDK + Kotlin CLI or run Gradle unit tests" >&2
    exit 2
  fi
done
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/stubs" "$TMP/runner"
javac -d "$TMP/stubs" "$ROOT/scripts/test-support/org/junit/Test.java" "$ROOT/scripts/test-support/org/junit/Assert.java"
kotlinc \
  "$ROOT/app/src/main/kotlin/io/github/lujinxin/nextep/display/ExchangeSession.kt" \
  "$ROOT/app/src/test/kotlin/io/github/lujinxin/nextep/display/ExchangeSessionTest.kt" \
  -classpath "$TMP/stubs" -include-runtime -d "$TMP/tests.jar"
javac -classpath "$TMP/stubs:$TMP/tests.jar" -d "$TMP/runner" "$ROOT/scripts/test-support/TestRunner.java"
java -classpath "$TMP/stubs:$TMP/tests.jar:$TMP/runner" TestRunner

kotlinc \
  "$ROOT/app/src/main/kotlin/io/github/lujinxin/nextep/display/ExchangeSession.kt" \
  "$ROOT/scripts/test-support/ExchangeSessionStress.kt" \
  -include-runtime -d "$TMP/stress.jar"
java -jar "$TMP/stress.jar"

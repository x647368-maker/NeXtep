#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/.."
log_path=$(mktemp)
trap 'rm -f "$log_path"' EXIT
for attempt in 1 2 3; do
  gradle_args=("$@")
  if [ "$attempt" -gt 1 ]; then
    # Retry failed remote resolution rather than the prior configuration result.
    gradle_args+=(--refresh-dependencies --no-configuration-cache)
  fi
  set +e
  bash gradlew "${gradle_args[@]}" --console=plain --stacktrace 2>&1 | tee "$log_path"
  result=${PIPESTATUS[0]}
  set -e
  if [ "$result" -eq 0 ]; then exit 0; fi
  # Compiler and assertion failures are never retried as network failures.
  if grep -Eiq '(^e:.*\.kt|\.java:[0-9]+: error:|Compilation error|compilation failed|There were failing tests|[0-9]+ tests completed, [1-9][0-9]* failed)' "$log_path"; then exit "$result"; fi
  if [ "$attempt" -ge 3 ] || ! grep -Eiq '(HTTP[^[:cntrl:]]*(502|503|504)|status code (502|503|504)|response code: (502|503|504)|timed out|Connection reset)' "$log_path" || ! grep -Eiq '(https?://|Could not (GET|HEAD|download)|Could not resolve)' "$log_path"; then exit "$result"; fi
  echo "Transient dependency download failure; retry $attempt/2."
  sleep "${ATOMIC_RETRY_DELAY_SECONDS:-10}"
done

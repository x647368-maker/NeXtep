# CI-only candidate verification

This pull request intentionally carries no application changes. Its base branch contains the exact application source from PR #29, plus an isolated pull_request trigger for CI verification. Do not merge or publish this branch.

Gate: Android compile + JVM tests + frozen source continuity + HWUI regression must pass before distributing a signed three-device APK. Real ColorOS device acceptance is separate.

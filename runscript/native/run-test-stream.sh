#!/bin/sh
set -eu

# KGP 2.2.21-0.3.0 feeds stdout/stderr to the same non-thread-safe Gradle
# TestOutputStore. Keep all diagnostics, but deliver them through one pipe.
: "${DEADLINER_NATIVE_TEST_EXECUTABLE:?Missing original Native test executable}"
exec "$DEADLINER_NATIVE_TEST_EXECUTABLE" "$@" 2>&1

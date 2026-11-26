#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
core_dir="$(cd -- "$script_dir/.." && pwd)"
source "$script_dir/lib/binaryArtifacts.sh"
deadliner_require_jdk
java_cmd="${JAVA_HOME:+$JAVA_HOME/bin/}java"
log_dir="$(mktemp -d "${TMPDIR:-/tmp}/deadliner-native-regression.XXXXXX")"
cd "$core_dir"

# No --tests filter, process splitting, collector override, or GC suppression.
for round in 1 2 3; do
    metadata_args=()
    if [[ "$round" != 1 ]]; then metadata_args=(-x generateKmpBuildMetadata); fi
    ./gradlew :shared:macosArm64Test --rerun "${metadata_args[@]}" --console=plain > "$log_dir/full-$round.log" 2>&1
    if rg -q 'ConcurrentModificationException|Multiple entries with same key|Error while processing test process output|Could not write XML test results' "$log_dir/full-$round.log"; then
        printf 'Native reporting failure: %s\n' "$log_dir/full-$round.log" >&2
        exit 1
    fi
    count="$("$java_cmd" "$script_dir/native/VerifyNativeResults.java" xml shared/build/test-results/macosArm64Test)"
    cp -R shared/build/test-results/macosArm64Test "$log_dir/results-$round"
    printf 'Full Native round %s: %s tests passed\n' "$round" "$count"
done
shared/build/bin/macosArm64/debugTest/test.kexe --ktest_logger=TEAMCITY --ktest_repeat=10 > "$log_dir/same-process-10.log" 2>&1
"$java_cmd" "$script_dir/native/VerifyNativeResults.java" events "$log_dir/same-process-10.log" "$((count * 10))"
printf 'Native regression evidence: %s\n' "$log_dir"

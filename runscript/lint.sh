#!/usr/bin/env bash
set -euo pipefail

# Standalone source analysis: never configure Gradle or resolve platform dependencies.
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$project_dir"
version=1.23.8
checksum=3afe89a11120303c73c9bdda3d8fe558dd9070a6937d27819ddc04b275381245
cache_dir="${DETEKT_CACHE_DIR:-$project_dir/build/tools/detekt}"
jar="$cache_dir/detekt-cli-$version-all.jar"
mkdir -p "$cache_dir" build/reports/detekt

verify_jar() {
    printf '%s  %s\n' "$checksum" "$1" | shasum -a 256 -c -
}

if [[ ! -f "$jar" ]]; then
    download="$(mktemp "$cache_dir/download.XXXXXX")"
    trap 'rm -f "$download"' EXIT
    curl --fail --location --silent --show-error --retry 3 --connect-timeout 15 --max-time 180 \
        "https://repo.maven.apache.org/maven2/io/gitlab/arturbosch/detekt/detekt-cli/$version/detekt-cli-$version-all.jar" \
        --output "$download"
    verify_jar "$download"
    mv "$download" "$jar"
    trap - EXIT
fi
verify_jar "$jar"

java_cmd=java
if [[ -n "${JAVA_HOME:-}" ]]; then
    java_cmd="$JAVA_HOME/bin/java"
fi
exec "$java_cmd" -jar "$jar" \
    --input shared/src,build.gradle.kts,settings.gradle.kts,runscript \
    --includes '**/*.kt,**/*.kts' \
    --excludes '**/build/**,**/resources/**' \
    --build-upon-default-config \
    --baseline config/detekt/baseline.xml \
    --base-path "$project_dir" \
    --report txt:build/reports/detekt/report.txt \
    --report html:build/reports/detekt/report.html \
    "$@"

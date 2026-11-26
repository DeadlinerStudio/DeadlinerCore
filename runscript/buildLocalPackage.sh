#!/usr/bin/env bash
# Prepare mobile libraries, the macOS framework and CLI, and the desktop JVM inputs.
set -euo pipefail
if [[ $# != 1 || ! $1 =~ ^v[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.-]+)?$ ]]; then
  echo 'Usage: runscript/buildLocalPackage.sh vMAJOR.MINOR.PATCH[-prerelease]' >&2
  exit 2
fi
[[ $(uname -sm) == 'Darwin arm64' ]] || { echo 'Run this stage on an Apple Silicon Mac.' >&2; exit 1; }
root=$(cd "$(dirname "$0")/.." && pwd)
tag=$1
output="$root/build/local-packages/$tag"
mkdir -p "$(dirname "$output")"
[[ ! -e "$output" ]] || { echo "Already built: $output" >&2; exit 1; }
work=$(mktemp -d "$(dirname "$output")/.build.XXXXXXXX")
trap 'rm -rf "$work"' EXIT

# Only committed inputs enter the build, including when the developer has LiFi edits.
snapshot() {
  local repo=$1 revision=$2 dest=$3
  git clone --quiet --no-checkout "$repo" "$dest"
  git -C "$dest" checkout --quiet --detach "$revision"
}
snapshot "$root" "$(git -C "$root" rev-parse HEAD)" "$work/core"
core="$work/core"
cli="$work/cli"
snapshot "${DEADLINER_LIFI_REPO:-$root/.private/lifi}" "$(cat "$core/lifi.revision")" "$core/.private/lifi"
snapshot "${DEADLINER_CLI_REPO:-https://github.com/DeadlinerStudio/DeadlinerCli.git}" "$(cat "$core/cli.revision")" "$cli"
snapshot "${DEADLINER_MAC_REPO:-$root/../Deadliner-macOS}" "${DEADLINER_MAC_REVISION:-HEAD}" "$work/mac"
cd "$core"
core_sha=$(git rev-parse HEAD)
./gradlew -PlifiMode=private :shared:downloadKotlinNativeDistribution
runscript/releaseBinaries.sh "$tag"
release="$core/build/releases/$tag"
./gradlew -PlifiMode=private -PdesktopCliDir="$cli" :shared:stageDesktopCli
./gradlew -PlifiMode=private --init-script "$work/mac/scripts/deadliner-mac.init.gradle" :shared:linkReleaseFrameworkMacosArm64
framework=shared/build/bin/macosArm64/releaseFramework/Shared.framework
[[ $(lipo -archs "$framework/Shared") == arm64 ]]
ditto -c -k --keepParent "$framework" "$release/deadliner-core-macos-arm64.framework.zip"
DEADLINER_CORE_DIR="$core" DEADLINER_LIFI_MODE=private "$cli/scripts/build.sh"
"$cli/bin/deadliner" --json version | python3 -c 'import json,sys; d=json.load(sys.stdin)["data"]; assert d["lifi"] and sys.argv[1][:6] in d["core"]' "$core_sha"
python3 "$cli/tests/integration.py"
mkdir "$work/cli-archive"
cp "$cli/bin/deadliner" "$cli/LICENSE" cli.revision lifi.revision "$work/cli-archive/"
printf '%s\n' "$core_sha" > "$work/cli-archive/core.revision"
tar -C "$work/cli-archive" -czf "$release/deadliner-macos-arm64.tar.gz" .

# Keep the native packaging inputs separate from the public binary allowlist.
mkdir "$work/bundle"
mv "$release" "$work/bundle/release"
mv shared/build/desktop-cli "$work/bundle/jars"
mkdir "$work/bundle/packager"
git -C "$cli" archive HEAD scripts tests/integration.py LICENSE | tar -x -C "$work/bundle/packager"
cp "$root/runscript/packageLocalDesktop.py" "$work/bundle/"
printf '%s\n' "$core_sha" > "$work/bundle/core.revision"
git -C "$work/mac" rev-parse HEAD > "$work/bundle/macos.revision"
python3 - "$work/bundle" "$output" <<'PY'
import os, sys
os.rename(sys.argv[1], sys.argv[2])
PY
printf 'Local build ready: %s\n' "$output"

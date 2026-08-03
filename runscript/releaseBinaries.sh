#!/usr/bin/env bash
# Build the closed-source-enabled KMP tree locally and optionally publish only
# its binary artifacts to a private GitHub Release.
set -euo pipefail

usage() {
  printf '%s\n' \
    'Usage: runscript/releaseBinaries.sh <version-tag> [--upload] [--repo OWNER/REPO]' \
    '' \
    'Examples:' \
    '  runscript/releaseBinaries.sh v0.2.0' \
    '  runscript/releaseBinaries.sh v0.2.0 --upload' \
    '' \
    'The script builds Android, Apple, and HarmonyOS locally. --upload publishes' \
    'only the generated binaries and checksums to a private or internal GitHub Release.'
}

if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
fi

if [[ $# -lt 1 ]]; then
  usage >&2
  exit 2
fi

tag="$1"
shift
upload=false
repository="${GITHUB_REPOSITORY:-}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --upload)
      upload=true
      ;;
    --repo)
      [[ $# -ge 2 ]] || { printf '%s\n' '--repo requires OWNER/REPO.' >&2; exit 2; }
      repository="$2"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      printf 'Unknown option: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
  shift
done

if [[ ! "$tag" =~ ^v[0-9][0-9A-Za-z._-]*$ ]]; then
  printf 'Version tag must start with v and contain only letters, digits, dot, underscore, or dash: %s\n' "$tag" >&2
  exit 2
fi

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"

if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == "Darwin" && -x /usr/libexec/java_home ]]; then
  detected_java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  if [[ -n "$detected_java_home" ]]; then
    export JAVA_HOME="$detected_java_home"
  fi
fi

java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"
if ! java_version="$("$java_command" -version 2>&1 | awk -F '[\".]' '/version/ { print $2; exit }')"; then
  printf '%s\n' 'A working JDK 17 or newer is required to package Deadliner Core.' >&2
  exit 1
fi

# Kotlin/Native may omit the generated Objective-C header and module map from
# one XCFramework slice even though another slice contains the same generated
# interface. Xcode then cannot import `Shared` when building a target for that
# omitted platform (most visibly extensions built for a physical device).
# The generated interface is platform-independent, so normalize every slice
# from the canonical header/module map before packaging the release.
normalize_apple_framework_modules() {
  local framework_root="$1"
  local source_header source_module source_info framework platform slice_index=0

  source_header="$(find "$framework_root" -path '*/Shared.framework/Headers/Shared.h' -type f -print -quit)"
  source_module="$(find "$framework_root" -path '*/Shared.framework/Modules/module.modulemap' -type f -print -quit)"
  source_info="$(find "$framework_root" -path '*/Shared.framework/Info.plist' -type f -print -quit)"
  [[ -n "$source_header" ]] || { printf '%s\n' 'No generated Shared.h found in XCFramework.' >&2; exit 1; }
  [[ -n "$source_module" ]] || { printf '%s\n' 'No generated Shared module map found in XCFramework.' >&2; exit 1; }
  [[ -n "$source_info" ]] || { printf '%s\n' 'No Shared.framework Info.plist found in XCFramework.' >&2; exit 1; }

  for framework in "$framework_root"/*/Shared.framework; do
    [[ -d "$framework" ]] || continue
    mkdir -p "$framework/Headers" "$framework/Modules"
    [[ -f "$framework/Headers/Shared.h" ]] || cp "$source_header" "$framework/Headers/Shared.h"
    [[ -f "$framework/Modules/module.modulemap" ]] || cp "$source_module" "$framework/Modules/module.modulemap"
    if [[ ! -f "$framework/Info.plist" ]]; then
      cp "$source_info" "$framework/Info.plist"
      if [[ "$(basename "$(dirname "$framework")")" == *simulator* ]]; then
        platform="iPhoneSimulator"
      else
        platform="iPhoneOS"
      fi
      plutil -replace CFBundleSupportedPlatforms -json "[\"$platform\"]" "$framework/Info.plist"
    fi
    if [[ -f "$(dirname "$framework")/dSYMs/Shared.framework.dSYM/Contents/Resources/DWARF/Shared" ]]; then
      /usr/libexec/PlistBuddy \
        -c "Add :AvailableLibraries:$slice_index:DebugSymbolsPath string dSYMs" \
        "$framework_root/Info.plist" 2>/dev/null || \
        /usr/libexec/PlistBuddy \
          -c "Set :AvailableLibraries:$slice_index:DebugSymbolsPath dSYMs" \
          "$framework_root/Info.plist"
    fi
    slice_index=$((slice_index + 1))
  done
}
if [[ "$java_version" == "1" ]]; then
  java_version="$("$java_command" -version 2>&1 | awk -F '[\".]' '/version/ { print $3; exit }')"
fi
if [[ ! "$java_version" =~ ^[0-9]+$ || "$java_version" -lt 17 ]]; then
  printf 'JDK 17 or newer is required; current Java major version is %s.\n' "$java_version" >&2
  printf '%s\n' 'Set JAVA_HOME to a JDK 17+ installation and retry.' >&2
  exit 1
fi

release_dir="$project_dir/build/releases/$tag"
staging_dir="${release_dir}.incomplete"
android_aar="$project_dir/shared/build/outputs/aar/shared-release.aar"
xcframework_dir="$project_dir/shared/build/XCFrameworks/release/Shared.xcframework"
ohos_library="$project_dir/shared/build/bin/ohosArm64/releaseShared/libDeadlinerCore.so"

if [[ -e "$release_dir" ]]; then
  printf 'Refusing to overwrite existing release directory: %s\n' "$release_dir" >&2
  exit 1
fi

if [[ -e "$staging_dir" ]]; then
  printf 'A previous incomplete build exists: %s\n' "$staging_dir" >&2
  printf '%s\n' 'Inspect or remove it before retrying this version.' >&2
  exit 1
fi

if [[ "$upload" == true && "$(uname -s)" != "Darwin" ]]; then
  printf '%s\n' 'Publishing all three artifacts requires macOS because the Apple XCFramework is built locally.' >&2
  exit 1
fi

if [[ "$upload" == true && -z "$repository" ]]; then
  origin_url="$(git -C "$project_dir" remote get-url origin)"
  case "$origin_url" in
    git@github.com:*) repository="${origin_url#git@github.com:}" ;;
    https://github.com/*) repository="${origin_url#https://github.com/}" ;;
    *)
      printf 'Cannot infer GitHub repository from origin: %s\n' "$origin_url" >&2
      printf '%s\n' 'Pass --repo OWNER/REPO.' >&2
      exit 2
      ;;
  esac
  repository="${repository%.git}"
fi

mkdir -p "$staging_dir"

printf 'Building release artifacts for %s...\n' "$tag"
(
  cd "$project_dir"
  ./gradlew \
    :shared:assembleRelease \
    :shared:assembleSharedReleaseXCFramework \
    :shared:linkReleaseSharedOhosArm64 \
    --no-daemon \
    --console=plain
)

for artifact in "$android_aar" "$xcframework_dir" "$ohos_library"; do
  if [[ ! -e "$artifact" ]]; then
    printf 'Expected build artifact was not produced: %s\n' "$artifact" >&2
    exit 1
  fi
done

normalize_apple_framework_modules "$xcframework_dir"

cp "$android_aar" "$staging_dir/deadliner-core-android.aar"
/bin/bash "$project_dir/runscript/verifyAppleDsyms.sh" \
  "$xcframework_dir" "$staging_dir/apple-dsym-uuids.txt"
/usr/bin/ditto -c -k --keepParent \
  "$xcframework_dir" "$staging_dir/deadliner-core-ios.xcframework.zip"
cp "$ohos_library" "$staging_dir/libDeadlinerCore.so"

source_revision="$(git -C "$project_dir" rev-parse HEAD)"
if git -C "$project_dir" diff --quiet && git -C "$project_dir" diff --cached --quiet; then
  source_dirty=false
else
  source_dirty=true
fi
built_at="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

(
  cd "$staging_dir"
  shasum -a 256 deadliner-core-android.aar deadliner-core-ios.xcframework.zip libDeadlinerCore.so apple-dsym-uuids.txt > checksums-sha256.txt
)

android_sha="$(awk '$2 == "deadliner-core-android.aar" { print $1 }' "$staging_dir/checksums-sha256.txt")"
apple_sha="$(awk '$2 == "deadliner-core-ios.xcframework.zip" { print $1 }' "$staging_dir/checksums-sha256.txt")"
ohos_sha="$(awk '$2 == "libDeadlinerCore.so" { print $1 }' "$staging_dir/checksums-sha256.txt")"

printf '{\n  "version": "%s",\n  "sourceRevision": "%s",\n  "sourceDirty": %s,\n  "builtAt": "%s",\n  "artifacts": [\n    {"platform": "android", "file": "deadliner-core-android.aar", "sha256": "%s"},\n    {"platform": "apple", "file": "deadliner-core-ios.xcframework.zip", "sha256": "%s"},\n    {"platform": "harmonyos-arm64", "file": "libDeadlinerCore.so", "sha256": "%s"}\n  ]\n}\n' \
  "$tag" "$source_revision" "$source_dirty" "$built_at" "$android_sha" "$apple_sha" "$ohos_sha" \
  > "$staging_dir/manifest.json"

mv "$staging_dir" "$release_dir"

printf '\nRelease artifacts are ready in: %s\n' "$release_dir"
printf 'Verify downloads with: (cd "%s" && shasum -a 256 -c checksums-sha256.txt)\n' "$release_dir"

if [[ "$upload" == false ]]; then
  printf '%s\n' 'Run again with --upload to create or update a private GitHub Release.'
  exit 0
fi

command -v gh >/dev/null || { printf '%s\n' 'GitHub CLI (gh) is required for --upload.' >&2; exit 1; }
gh auth status --hostname github.com >/dev/null

visibility="$(gh repo view "$repository" --json visibility --jq '.visibility')"
if [[ "$visibility" != "PRIVATE" && "$visibility" != "INTERNAL" ]]; then
  printf 'Refusing to upload closed-source binaries to %s repository %s.\n' "$visibility" "$repository" >&2
  exit 1
fi

release_files=(
  "$release_dir/deadliner-core-android.aar"
  "$release_dir/deadliner-core-ios.xcframework.zip"
  "$release_dir/libDeadlinerCore.so"
  "$release_dir/apple-dsym-uuids.txt"
  "$release_dir/checksums-sha256.txt"
  "$release_dir/manifest.json"
)

if gh release view "$tag" --repo "$repository" >/dev/null 2>&1; then
  gh release upload "$tag" "${release_files[@]}" --repo "$repository" --clobber
else
  gh release create "$tag" "${release_files[@]}" \
    --repo "$repository" \
    --target "$source_revision" \
    --title "Deadliner Core $tag" \
    --notes "Private binary distribution. Verify every artifact with checksums-sha256.txt."
fi

printf 'Published %s to https://github.com/%s/releases/tag/%s\n' "$tag" "$repository" "$tag"

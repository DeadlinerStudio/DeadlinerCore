#!/usr/bin/env bash
# Build every client binary and deploy it to the matching local repository.
set -euo pipefail

usage() {
  printf '%s\n' \
    'Usage: runscript/buildAndDeploy.sh [options]' \
    '' \
    'With no options, builds Android, iOS, and HarmonyOS release binaries and' \
    'deploys them to the sibling repositories in ProjectDeadliner.' \
    '' \
    'Options:' \
    '  --android-repo PATH  Override the Android repository path.' \
    '  --ios-repo PATH      Override the iOS repository path.' \
    '  --harmony-repo PATH  Override the HarmonyOS repository path.' \
    '  --skip-android       Do not build or deploy the Android AAR.' \
    '  --skip-ios           Do not build or deploy the Apple XCFramework.' \
    '  --skip-harmony       Do not build or deploy the HarmonyOS library.' \
    '  --no-build           Deploy existing build outputs without rebuilding.' \
    '  --dry-run            Validate paths and print the work without changing files.' \
    '  -h, --help           Show this help.' \
    '' \
    'The repository paths can also be set with DEADLINER_ANDROID_REPO,' \
    'DEADLINER_IOS_REPO, and DEADLINER_HARMONY_REPO.'
}

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
project_dir="$(cd -- "$script_dir/.." && pwd)"
studio_dir="$(cd -- "$project_dir/.." && pwd)"

# shellcheck source=runscript/lib/binaryArtifacts.sh
source "$script_dir/lib/binaryArtifacts.sh"

android_repo="${DEADLINER_ANDROID_REPO:-$studio_dir/Deadliner}"
ios_repo="${DEADLINER_IOS_REPO:-$studio_dir/Deadliner-iOS}"
harmony_repo="${DEADLINER_HARMONY_REPO:-$studio_dir/DeadlinerNEXT}"
deploy_android=true
deploy_ios=true
deploy_harmony=true
build=true
dry_run=false

while [[ $# -gt 0 ]]; do
  case "$1" in
    --android-repo)
      [[ $# -ge 2 ]] || { printf '%s\n' '--android-repo requires a path.' >&2; exit 2; }
      android_repo="$2"
      shift
      ;;
    --ios-repo)
      [[ $# -ge 2 ]] || { printf '%s\n' '--ios-repo requires a path.' >&2; exit 2; }
      ios_repo="$2"
      shift
      ;;
    --harmony-repo)
      [[ $# -ge 2 ]] || { printf '%s\n' '--harmony-repo requires a path.' >&2; exit 2; }
      harmony_repo="$2"
      shift
      ;;
    --skip-android) deploy_android=false ;;
    --skip-ios) deploy_ios=false ;;
    --skip-harmony) deploy_harmony=false ;;
    --no-build) build=false ;;
    --dry-run) dry_run=true ;;
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

if [[ "$deploy_android" == false && "$deploy_ios" == false && "$deploy_harmony" == false ]]; then
  printf '%s\n' 'Nothing to do: every platform was skipped.' >&2
  exit 2
fi

android_aar="$project_dir/shared/build/outputs/aar/shared-release.aar"
xcframework_dir="$project_dir/shared/build/XCFrameworks/release/Shared.xcframework"
ohos_library="$project_dir/shared/build/bin/ohosArm64/releaseShared/libDeadlinerCore.so"

android_target="$android_repo/.deadliner-core/kmp/deadliner-core.aar"
ios_target="$ios_repo/Vendor/KMP/shared.xcframework"
harmony_target="$harmony_repo/entry/libs/arm64-v8a/libDeadlinerCore.so"

require_repo_marker() {
  local enabled="$1" repo="$2" marker="$3" label="$4"
  [[ "$enabled" == true ]] || return 0
  if [[ ! -e "$repo/$marker" ]]; then
    printf '%s repository was not found at %s (missing %s).\n' "$label" "$repo" "$marker" >&2
    return 1
  fi
}

require_repo_marker "$deploy_android" "$android_repo" "app/build.gradle.kts" "Android"
require_repo_marker "$deploy_ios" "$ios_repo" "Deadliner.xcodeproj/project.pbxproj" "iOS"
require_repo_marker "$deploy_harmony" "$harmony_repo" "entry/build-profile.json5" "HarmonyOS"

if [[ "$deploy_ios" == true && "$(uname -s)" != "Darwin" ]]; then
  printf '%s\n' 'The Apple XCFramework build and deployment require macOS.' >&2
  exit 1
fi

printf '%s\n' 'Deadliner Core build and deployment plan:'
[[ "$deploy_android" == true ]] && printf '  Android:  %s\n' "$android_target"
[[ "$deploy_ios" == true ]] && printf '  iOS:      %s\n' "$ios_target"
[[ "$deploy_harmony" == true ]] && printf '  Harmony:  %s\n' "$harmony_target"
if [[ "$build" == false ]]; then
  printf '%s\n' '  Build:    skipped; existing outputs will be used'
fi

if [[ "$dry_run" == true ]]; then
  printf '%s\n' 'Dry run complete; no files were changed.'
  exit 0
fi

if [[ "$build" == true ]]; then
  deadliner_require_jdk
  gradle_tasks=()
  [[ "$deploy_android" == true ]] && gradle_tasks+=(":shared:assembleRelease")
  [[ "$deploy_ios" == true ]] && gradle_tasks+=(":shared:assembleSharedReleaseXCFramework")
  [[ "$deploy_harmony" == true ]] && gradle_tasks+=(":shared:linkReleaseSharedOhosArm64")

  printf '\nBuilding release binaries...\n'
  (
    cd "$project_dir"
    ./gradlew "${gradle_tasks[@]}" --no-daemon --console=plain
  )
fi

expected_outputs=()
[[ "$deploy_android" == true ]] && expected_outputs+=("$android_aar")
[[ "$deploy_ios" == true ]] && expected_outputs+=("$xcframework_dir")
[[ "$deploy_harmony" == true ]] && expected_outputs+=("$ohos_library")
for artifact in "${expected_outputs[@]}"; do
  if [[ ! -e "$artifact" ]]; then
    printf 'Expected build artifact was not produced: %s\n' "$artifact" >&2
    exit 1
  fi
done

if [[ "$deploy_ios" == true ]]; then
  deadliner_normalize_apple_framework_modules "$xcframework_dir"
fi

deploy_file() {
  local source_file="$1" target_file="$2" label="$3"
  local target_parent temp_file
  target_parent="$(dirname "$target_file")"
  mkdir -p "$target_parent"
  temp_file="$(mktemp "$target_parent/.deadliner-core-deploy.XXXXXX")"
  cp -p "$source_file" "$temp_file"
  mv -f "$temp_file" "$target_file"
  if ! cmp -s "$source_file" "$target_file"; then
    printf '%s deployment verification failed: %s\n' "$label" "$target_file" >&2
    return 1
  fi
  printf '  %-8s %s\n' "$label" "$target_file"
}

deploy_directory() {
  local source_dir="$1" target_dir="$2" label="$3"
  local target_parent temp_root staged_dir backup_dir
  target_parent="$(dirname "$target_dir")"
  mkdir -p "$target_parent"
  temp_root="$(mktemp -d "$target_parent/.deadliner-core-deploy.XXXXXX")"
  staged_dir="$temp_root/staged.xcframework"
  backup_dir="$temp_root/previous.xcframework"
  (
    restore_previous=false
    cleanup_directory_deploy() {
      if [[ "$restore_previous" == true && ! -e "$target_dir" && -e "$backup_dir" ]]; then
        mv "$backup_dir" "$target_dir"
      fi
      rm -rf "$temp_root"
    }
    trap cleanup_directory_deploy EXIT
    trap 'exit 130' INT
    trap 'exit 143' TERM

    /usr/bin/ditto "$source_dir" "$staged_dir"
    if [[ -e "$target_dir" ]]; then
      mv "$target_dir" "$backup_dir"
      restore_previous=true
    fi
    mv "$staged_dir" "$target_dir"
    restore_previous=false

    if ! diff -qr "$source_dir" "$target_dir" >/dev/null; then
      rm -rf "$target_dir"
      [[ ! -e "$backup_dir" ]] || mv "$backup_dir" "$target_dir"
      printf '%s deployment verification failed; the previous artifact was restored.\n' "$label" >&2
      exit 1
    fi
  )
  printf '  %-8s %s\n' "$label" "$target_dir"
}

printf '\nDeploying verified binaries...\n'
[[ "$deploy_android" == false ]] || deploy_file "$android_aar" "$android_target" "Android"
[[ "$deploy_ios" == false ]] || deploy_directory "$xcframework_dir" "$ios_target" "iOS"
[[ "$deploy_harmony" == false ]] || deploy_file "$ohos_library" "$harmony_target" "Harmony"

printf '\n%s\n' 'All selected Deadliner Core binaries are built, deployed, and byte-verified.'

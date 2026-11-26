#!/usr/bin/env bash

# Shared helpers for scripts that package or deploy Deadliner Core binaries.
# The caller is responsible for enabling its preferred shell safety options.

deadliner_require_jdk() {
  if [[ -z "${JAVA_HOME:-}" && "$(uname -s)" == "Darwin" && -x /usr/libexec/java_home ]]; then
    local detected_java_home
    detected_java_home="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
    if [[ -n "$detected_java_home" ]]; then
      export JAVA_HOME="$detected_java_home"
    fi
  fi

  local java_command java_version
  java_command="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  if ! java_version="$("$java_command" -version 2>&1 | awk -F '[\".]' '/version/ { print $2; exit }')"; then
    printf '%s\n' 'A working JDK 17 or newer is required to build Deadliner Core.' >&2
    return 1
  fi
  if [[ "$java_version" == "1" ]]; then
    java_version="$("$java_command" -version 2>&1 | awk -F '[\".]' '/version/ { print $3; exit }')"
  fi
  if [[ ! "$java_version" =~ ^[0-9]+$ || "$java_version" -lt 17 ]]; then
    printf 'JDK 17 or newer is required; current Java major version is %s.\n' "$java_version" >&2
    printf '%s\n' 'Set JAVA_HOME to a JDK 17+ installation and retry.' >&2
    return 1
  fi
}

# Kotlin/Native may omit import metadata from one XCFramework slice even though
# another slice contains the same generated interface. Normalize every slice
# before either packaging or deploying the framework.
deadliner_normalize_apple_framework_modules() {
  local framework_root="$1"
  local source_header source_module source_info framework platform slice_index=0

  source_header="$(find "$framework_root" -path '*/Shared.framework/Headers/Shared.h' -type f -print -quit)"
  source_module="$(find "$framework_root" -path '*/Shared.framework/Modules/module.modulemap' -type f -print -quit)"
  source_info="$(find "$framework_root" -path '*/Shared.framework/Info.plist' -type f -print -quit)"
  [[ -n "$source_header" ]] || { printf '%s\n' 'No generated Shared.h found in XCFramework.' >&2; return 1; }
  [[ -n "$source_module" ]] || { printf '%s\n' 'No generated Shared module map found in XCFramework.' >&2; return 1; }
  [[ -n "$source_info" ]] || { printf '%s\n' 'No Shared.framework Info.plist found in XCFramework.' >&2; return 1; }

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

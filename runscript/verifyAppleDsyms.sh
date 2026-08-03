#!/usr/bin/env bash
set -euo pipefail

xcframework_dir="${1:?Usage: verifyAppleDsyms.sh /path/to/Shared.xcframework [report-path]}"
report_path="${2:-}"

command -v dwarfdump >/dev/null || { printf '%s\n' 'dwarfdump is required.' >&2; exit 1; }
[[ -d "$xcframework_dir" ]] || { printf 'XCFramework not found: %s\n' "$xcframework_dir" >&2; exit 1; }

report=""
for framework in "$xcframework_dir"/*/Shared.framework; do
  [[ -d "$framework" ]] || continue
  slice_dir="$(dirname "$framework")"
  binary="$framework/Shared"
  dwarf="$slice_dir/dSYMs/Shared.framework.dSYM/Contents/Resources/DWARF/Shared"
  [[ -f "$binary" && -f "$dwarf" ]] || { printf 'Missing Shared dSYM: %s\n' "$slice_dir" >&2; exit 1; }
  binary_uuids="$(dwarfdump --uuid "$binary" | awk '{print $2}' | sort)"
  dwarf_uuids="$(dwarfdump --uuid "$dwarf" | awk '{print $2}' | sort)"
  [[ -n "$binary_uuids" && "$binary_uuids" == "$dwarf_uuids" ]] || {
    printf 'Shared dSYM UUID mismatch: %s\n' "$slice_dir" >&2
    exit 1
  }
  report+="$(basename "$slice_dir") $binary_uuids"$'\n'
done

[[ -n "$report" ]] || { printf '%s\n' 'No Shared.framework slices found.' >&2; exit 1; }
if [[ -n "$report_path" ]]; then printf '%s' "$report" > "$report_path"; else printf '%s' "$report"; fi

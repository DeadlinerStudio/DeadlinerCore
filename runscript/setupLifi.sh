#!/usr/bin/env bash
# Explicit opt-in only: public builds do not invoke this script.
set -euo pipefail
project_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
checkout="$project_dir/.private/lifi"
revision="$(tr -d '\r\n' < "$project_dir/lifi.revision")"
[[ "$revision" =~ ^[0-9a-f]{40}$ ]] || { echo 'Invalid lifi.revision' >&2; exit 1; }
if [[ ! -e "$checkout" ]]; then
  mkdir -p "$project_dir/.private"
  git clone git@github.com:DeadlinerStudio/Lifi.git "$checkout"
fi
[[ "$(git -C "$checkout" rev-parse --show-toplevel)" == "$checkout" ]] || {
  echo 'Expected an independent Lifi checkout in .private/lifi' >&2; exit 1;
}
[[ -z "$(git -C "$checkout" status --porcelain)" ]] || {
  echo 'Lifi checkout has local changes; commit or stash them before setup.' >&2; exit 1;
}
origin="$(git -C "$checkout" remote get-url origin)"
case "$origin" in
  git@github.com:DeadlinerStudio/Lifi.git|https://github.com/DeadlinerStudio/Lifi.git) ;;
  *) echo 'Unexpected Lifi remote; refusing to fetch.' >&2; exit 1 ;;
esac
git -C "$checkout" fetch origin "$revision"
git -C "$checkout" checkout --detach "$revision"
printf 'Lifi ready at %s. Build with -PlifiMode=private.\n' "$revision"

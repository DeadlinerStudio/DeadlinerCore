#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$script_dir/.." && pwd)"
migration_dir="$repo_dir/shared/src/commonMain/sqldelight/com/deadliner/db/migrations"
fixture_db="$migration_dir/1.db"
work_dir="$(mktemp -d "${TMPDIR:-/tmp}/deadliner-habit-migration.XXXXXX")"
legacy_db="$work_dir/habit-v4.db"
backup_db="$work_dir/habit-v4.backup.db"
restored_db="$work_dir/habit-v4.restored.db"

cleanup() {
  rm -rf "$work_dir"
}
trap cleanup EXIT

assert_sql() {
  local expected="$1"
  local database="$2"
  local query="$3"
  local actual
  actual="$(sqlite3 -batch -noheader "$database" "$query")"
  if [[ "$actual" != "$expected" ]]; then
    echo "Expected '$expected' but got '$actual' for: $query" >&2
    exit 1
  fi
}

command -v sqlite3 >/dev/null
test -f "$fixture_db"

cp "$fixture_db" "$legacy_db"
sqlite3 -batch -bail "$legacy_db" < "$migration_dir/1.sqm"
sqlite3 -batch -bail "$legacy_db" < "$migration_dir/2.sqm"
sqlite3 -batch -bail "$legacy_db" < "$migration_dir/3.sqm"

sqlite3 -batch -bail "$legacy_db" <<'SQL'
PRAGMA foreign_keys = ON;
PRAGMA user_version = 4;

INSERT INTO category (
  uid, name, icon_key, color_hex, is_preset, sort_order, created_at, updated_at, is_deleted
) VALUES (
  'category.study', '学习', 'book', '#2563EB', 0, 0,
  '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', 0
);

INSERT INTO habit VALUES
  ('habit.daily', 'Daily sample', NULL, NULL, NULL, 'category.study', 'DAILY', 20, 'PER_PERIOD', NULL, 'ACTIVE', 0, '08:30', 1, '2026-01-02T10:30:00Z', '2026-01-02T10:30:00Z', 0),
  ('habit.weekly', 'Weekly sample', NULL, NULL, NULL, NULL, 'WEEKLY', 3, 'PER_PERIOD', NULL, 'ACTIVE', 1, NULL, NULL, '2026-01-03T10:30:00Z', '2026-01-03T10:30:00Z', 0),
  ('habit.total', 'Total fallback sample', NULL, NULL, NULL, NULL, 'MONTHLY', 1, 'TOTAL', NULL, 'ACTIVE', 2, NULL, NULL, '2026-01-04T10:30:00Z', '2026-01-04T10:30:00Z', 0),
  ('habit.ebbinghaus', 'Ebbinghaus sample', NULL, NULL, NULL, NULL, 'EBBINGHAUS', 9, 'PER_PERIOD', NULL, 'ARCHIVED', 3, '09:00', 1, '2026-01-05T10:30:00Z', '2026-01-05T10:30:00Z', 0),
  ('habit.once', 'Once sample', NULL, NULL, NULL, NULL, 'ONCE', 1, 'PER_PERIOD', NULL, 'ACTIVE', 4, NULL, NULL, '2026-01-06', '2026-01-06', 0);

INSERT INTO habit_record VALUES
  ('record.total.1', 'habit.total', '2026-01-05', 4, 'COMPLETED', '2026-01-05T11:00:00Z', '2026-01-05T11:00:00Z', 0),
  ('record.total.2', 'habit.total', '2026-01-06', 3, 'COMPLETED', '2026-01-06T11:00:00Z', '2026-01-06T11:00:00Z', 0),
  ('record.deleted', 'habit.total', '2026-01-07', 99, 'COMPLETED', '2026-01-07T11:00:00Z', '2026-01-07T11:00:00Z', 1);

INSERT INTO habit_schedule_item VALUES
  ('schedule.1', 'habit.daily', '2026-01-08T08:30:00', 'PENDING', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', 0),
  ('schedule.2', 'habit.ebbinghaus', '2026-01-09T09:00:00', 'PENDING', '2026-01-01T00:00:00Z', '2026-01-01T00:00:00Z', 0);
SQL

cp "$legacy_db" "$backup_db"
sqlite3 -batch -bail "$legacy_db" < "$migration_dir/4.sqm"
sqlite3 -batch -bail "$legacy_db" "PRAGMA user_version = 5;"

assert_sql "0" "$legacy_db" "SELECT COUNT(*) FROM pragma_foreign_key_check;"
assert_sql "5" "$legacy_db" "PRAGMA user_version;"
assert_sql "0" "$legacy_db" "SELECT COUNT(*) FROM pragma_table_info('habit') WHERE name IN ('period','times_per_period','goal_type','total_target');"
assert_sql "0" "$legacy_db" "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='habit_schedule_item';"
assert_sql "2" "$legacy_db" "SELECT diagnostic_value FROM migration_diagnostic WHERE diagnostic_key='habit_schedule_item_discarded_v5';"
assert_sql "3" "$legacy_db" "SELECT COUNT(*) FROM habit_record;"

assert_sql "WEEKLY_DAYS|2026-01-02|127|SCHEDULED_DAY|20|QUANTITY|08:30|1" "$legacy_db" \
  "SELECT schedule_kind,schedule_start_on,schedule_weekdays_mask,goal_window,goal_target_count,tracking_mode,reminder_local_time,reminder_enabled FROM habit WHERE uid='habit.daily';"
assert_sql "WEEKLY_DAYS|WEEK|3|QUANTITY" "$legacy_db" \
  "SELECT schedule_kind,goal_window,goal_target_count,tracking_mode FROM habit WHERE uid='habit.weekly';"
assert_sql "WEEKLY_DAYS|127|LIFETIME|7|QUANTITY" "$legacy_db" \
  "SELECT schedule_kind,schedule_weekdays_mask,goal_window,goal_target_count,tracking_mode FROM habit WHERE uid='habit.total';"
assert_sql "ALGORITHM|2026-01-05|EBBINGHAUS_CLASSIC_V1|SCHEDULED_DAY|1|CHECK_OFF|ARCHIVED" "$legacy_db" \
  "SELECT schedule_kind,schedule_start_on,schedule_algorithm_key,goal_window,goal_target_count,tracking_mode,status FROM habit WHERE uid='habit.ebbinghaus';"
assert_sql "ONCE|2026-01-06||SCHEDULED_DAY|1|CHECK_OFF" "$legacy_db" \
  "SELECT schedule_kind,schedule_start_on,schedule_weekdays_mask,goal_window,goal_target_count,tracking_mode FROM habit WHERE uid='habit.once';"

cp "$backup_db" "$restored_db"
assert_sql "4" "$restored_db" "PRAGMA user_version;"
assert_sql "4" "$restored_db" "SELECT COUNT(*) FROM pragma_table_info('habit') WHERE name IN ('period','times_per_period','goal_type','total_target');"
assert_sql "2" "$restored_db" "SELECT COUNT(*) FROM habit_schedule_item;"
assert_sql "TOTAL||3" "$restored_db" "SELECT goal_type,total_target,(SELECT COUNT(*) FROM habit_record) FROM habit WHERE uid='habit.total';"

echo "Habit v4 -> v5 migration and backup restore verification passed."

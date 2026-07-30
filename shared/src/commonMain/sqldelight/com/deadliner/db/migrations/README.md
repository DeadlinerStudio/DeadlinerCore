# Migration policy

`deadliner_new_era.db` is an isolated preview database. The committed `1.db` is the Phase 1 baseline schema; `1.sqm` upgrades that baseline with the `legacy_v2_version` table required by Phase 2 reconciliation.

Schema v3 adds persisted LiFi conversation turns. `2.sqm` upgrades existing v2
databases without touching task, habit, memory, or profile rows.

For every subsequent released schema change, commit the pre-migration `<version>.db` schema snapshot and a matching `<version>.sqm` migration. The Gradle `verifySqlDelightMigration` task must pass before the change is accepted.

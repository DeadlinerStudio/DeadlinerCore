# Migration policy

`deadliner_new_era.db` is an isolated preview database with no predecessor schema, so this initial release intentionally contains no `.sqm` migration. A numbered `.sqm` file must be added only when a released NEW ERA schema changes; it must migrate the immediately previous schema and pass SQLDelight migration verification.

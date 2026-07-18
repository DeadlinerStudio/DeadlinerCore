# Android integration example

Add `implementation(project(":shared"))` to the existing Android app module, then create `NewEraDatabaseProvider(applicationContext)` in the app's `data` layer. The provider creates `deadliner_new_era.db`, so it cannot read or overwrite the existing v5 database.

The sample deliberately contains no activity, Compose code, or manifest: the preview is a persistence-only integration.

# iOS integration example

1. Build `:shared:assembleSharedXCFramework` and add the generated `Shared.xcframework` to the existing Xcode target after the shared persistence API exists.
2. Add `-lsqlite3` to **Build Settings → Other Linker Flags**.
3. Create the persistence preview in the existing `Data` composition root and pass repositories into `Core/Application` use cases.

The domain foundation does not yet expose `DeadlinerDatabase` or a driver factory. This integration is deferred until the SQLDelight schema phase; it will not modify SwiftUI or the existing v5 store.

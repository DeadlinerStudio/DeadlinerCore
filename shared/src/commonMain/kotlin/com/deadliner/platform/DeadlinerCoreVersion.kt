package com.deadliner.platform

/**
 * Version of the KMP core embedded when this artifact was compiled.
 *
 * Host applications should use [value] for diagnostics and [bridgeVersion] for the existing
 * HarmonyOS ABI compatibility gate. The identifier records the source commit, Git branch,
 * and build user. Uncommitted edits are compiled normally but share their HEAD commit identifier.
 */
object DeadlinerCoreVersion {
    /** `DeadlinerCore-commit6-branch@user`, using the first six commit characters. */
    val value: String
        get() = KmpBuildMetadata.coreVersion

    /** UTC commit hour in `YYYYMMDD-HH` form, unless overridden by the build. */
    val compileDate: String
        get() = KmpBuildMetadata.compileDate

    /** Git branch recorded for this binary. */
    val compileBranch: String
        get() = KmpBuildMetadata.compileBranch

    /** Username that compiled this binary. */
    val compileUserName: String
        get() = KmpBuildMetadata.compileUserName

    /** Integer retained for the existing HarmonyOS bridge compatibility API. */
    val bridgeVersion: Int
        get() = KmpBuildMetadata.bridgeVersion

    /** UTC commit instant, unless overridden by the build; retained under its legacy API name. */
    val compiledAtUtc: String
        get() = KmpBuildMetadata.compiledAtUtc
}

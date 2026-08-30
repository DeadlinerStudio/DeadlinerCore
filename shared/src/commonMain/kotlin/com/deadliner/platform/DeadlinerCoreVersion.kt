package com.deadliner.platform

/**
 * Version of the KMP core embedded when this artifact was compiled.
 *
 * Host applications should use [value] for diagnostics and [bridgeVersion] for the existing
 * HarmonyOS ABI compatibility gate. The identifier is generated from compile date, Git branch,
 * and compile user name, so it identifies the binary that is actually linked rather than the
 * source checkout that built it.
 */
object DeadlinerCoreVersion {
    /** `DeadlinerCore-YYYYMMDD-HH-branch@user`, generated at compile time. */
    val value: String
        get() = KmpBuildMetadata.coreVersion

    /** UTC compile hour in `YYYYMMDD-HH` form. */
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

    /** UTC instant at which the containing KMP artifact was compiled. */
    val compiledAtUtc: String
        get() = KmpBuildMetadata.compiledAtUtc
}

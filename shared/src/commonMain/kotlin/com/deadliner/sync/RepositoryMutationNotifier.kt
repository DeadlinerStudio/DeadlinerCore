package com.deadliner.sync

/** Receives a signal only after a local business transaction has committed successfully. */
fun interface RepositoryMutationNotifier {
    fun notifyMutation()

    companion object {
        val NONE = RepositoryMutationNotifier {}
    }
}

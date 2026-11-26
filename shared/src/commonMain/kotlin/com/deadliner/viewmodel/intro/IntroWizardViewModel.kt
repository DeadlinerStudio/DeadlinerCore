package com.deadliner.viewmodel.intro

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Platform-neutral state machine for the v5/v6 interactive onboarding walkthrough. */
class IntroWizardViewModel {
    private val _state = MutableStateFlow(IntroWizardState())
    val state: StateFlow<IntroWizardState> = _state.asStateFlow()

    fun onAddEntryClicked() = moveTo(IntroWizardStep.AddEntryInfo)

    fun onAddEntryInfoNext() = moveTo(IntroWizardStep.SwipeRightComplete)

    fun onSwipeRightComplete() = moveTo(IntroWizardStep.SwipeLeftDelete)

    fun onSwipeLeftDelete() = moveTo(IntroWizardStep.AiEntry)

    fun onAiEntryTriggered() = moveTo(IntroWizardStep.AiInfo)

    fun onAiInfoNext() = moveTo(IntroWizardStep.Done)

    fun next() {
        moveTo(
            when (_state.value.currentStep) {
                IntroWizardStep.AddEntry -> IntroWizardStep.AddEntryInfo
                IntroWizardStep.AddEntryInfo -> IntroWizardStep.SwipeRightComplete
                IntroWizardStep.SwipeRightComplete -> IntroWizardStep.SwipeLeftDelete
                IntroWizardStep.SwipeLeftDelete -> IntroWizardStep.AiEntry
                IntroWizardStep.AiEntry -> IntroWizardStep.AiInfo
                IntroWizardStep.AiInfo -> IntroWizardStep.Done
                IntroWizardStep.Done -> IntroWizardStep.Done
            },
        )
    }

    fun reset() = moveTo(IntroWizardStep.AddEntry)

    private fun moveTo(step: IntroWizardStep) {
        _state.update { it.copy(currentStep = step) }
    }
}

data class IntroWizardState(
    val currentStep: IntroWizardStep = IntroWizardStep.AddEntry,
)

sealed class IntroWizardStep {
    data object AddEntry : IntroWizardStep()
    data object AddEntryInfo : IntroWizardStep()
    data object SwipeRightComplete : IntroWizardStep()
    data object SwipeLeftDelete : IntroWizardStep()
    data object AiEntry : IntroWizardStep()
    data object AiInfo : IntroWizardStep()
    data object Done : IntroWizardStep()
}

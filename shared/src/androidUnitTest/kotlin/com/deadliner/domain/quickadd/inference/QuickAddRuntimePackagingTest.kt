package com.deadliner.domain.quickadd.inference

import kotlin.test.*
import kotlinx.coroutines.runBlocking

class QuickAddRuntimePackagingTest {
    @Test fun runtimeFreeBuildCreatesEngineWithoutClassLinkageFailure() = runBlocking {
        val engine = QuickAddInferenceEngine()
        assertFalse(engine.runtimeAvailable)
        assertEquals("UNAVAILABLE_ONNX_RUNTIME", engine.backendName)
        assertFalse(engine.isLoaded())
        engine.close()
    }
}

package io.github.ompreview

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class OmpReviewPluginTest : BasePlatformTestCase() {

    fun testPluginLoads() {
        // Verify the plugin initializes without error
        assertNotNull(project)
    }
}

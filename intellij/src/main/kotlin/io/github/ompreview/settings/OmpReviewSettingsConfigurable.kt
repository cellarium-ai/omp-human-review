package io.github.ompreview.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.panel
import io.github.ompreview.services.OmpReviewProjectService

class OmpReviewSettingsConfigurable(private val project: Project) : BoundConfigurable("Human Review") {

    private val settings get() = OmpReviewSettings.getInstance(project)

    override fun createPanel(): DialogPanel = panel {
        row("Directory:") {
            textField()
                .bindText(
                    getter = { settings.state.ompReviewDirPath },
                    setter = { settings.state.ompReviewDirPath = it.trim() },
                )
                .columns(COLUMNS_LARGE)
                .comment(
                    "Absolute path or relative to the project root. " +
                    "Leave blank to use <b>&lt;project root&gt;/.omp-review</b>."
                )
        }
    }

    override fun apply() {
        super.apply()   // writes bound text → settings.state
        project.service<OmpReviewProjectService>().onSettingsChanged()
    }
}

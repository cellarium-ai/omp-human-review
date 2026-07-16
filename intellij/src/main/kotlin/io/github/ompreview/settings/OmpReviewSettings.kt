package io.github.ompreview.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(name = "OmpReviewSettings", storages = [Storage("omp-review-settings.xml")])
class OmpReviewSettings : PersistentStateComponent<OmpReviewSettings.State> {

    /** Plain class (no-arg ctor) required by IntelliJ's XML serializer. */
    class State {
        var ompReviewDirPath: String = ""
    }

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): OmpReviewSettings =
            project.getService(OmpReviewSettings::class.java)
    }
}

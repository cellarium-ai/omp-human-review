package io.github.ompreview.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.util.messages.Topic
import io.github.ompreview.model.ReviewFile
import io.github.ompreview.model.ReviewMarkdown
import io.github.ompreview.model.ReviewState
import io.github.ompreview.settings.OmpReviewSettings
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

@Service(Service.Level.PROJECT)
class OmpReviewProjectService(private val project: Project) {

    interface Listener {
        fun stateChanged(state: ReviewState?)
    }

    companion object {
        val TOPIC: Topic<Listener> = Topic.create("Human Review State", Listener::class.java)
    }

    var currentState: ReviewState? = null
        private set

    /** Re-derives the changed-file list from git and merges in persisted comments. */
    fun reload() {
        val ompReviewDir = resolveOmpReviewDir()
        if (ompReviewDir == null) {
            update(null)
            return
        }

        val reviewFile = ompReviewDir.resolve("review.md")
        val persisted = if (reviewFile.exists()) {
            try { ReviewMarkdown.parse(reviewFile.readText()) } catch (_: Exception) { ReviewState() }
        } else {
            ReviewState()
        }

        val root = repoRoot()
        val changedFiles = if (root != null) computeChangedFiles(root) else emptyList()
        val commentsByFile = persisted.files.associateBy { it.file }
        val files = changedFiles.map { file -> ReviewFile(file, commentsByFile[file]?.comments ?: emptyList()) }

        update(ReviewState(persisted.status, files))
    }

    /** Files git reports changed against HEAD — tracked diffs plus untracked new files. Never stages anything. */
    private fun computeChangedFiles(root: File): List<String> {
        val tracked = runGit(root, "diff", "--name-only", "HEAD")
        val untracked = runGit(root, "ls-files", "--others", "--exclude-standard")
        return (tracked + untracked).distinct()
    }

    private fun runGit(root: File, vararg args: String): List<String> {
        return try {
            val proc = ProcessBuilder("git", *args).directory(root).start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            if (proc.exitValue() == 0) out.lines().map(String::trim).filter(String::isNotEmpty) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Returns the active .omp-review directory: the user-configured path if set,
     * otherwise <project root>/.omp-review.
     */
    fun resolveOmpReviewDir(): Path? {
        val basePath = project.basePath ?: return null
        val override = OmpReviewSettings.getInstance(project).state.ompReviewDirPath.trim()
        return if (override.isNotEmpty()) {
            val p = Path.of(override)
            if (p.isAbsolute) p else Paths.get(basePath).resolve(p)
        } else {
            Paths.get(basePath, ".omp-review")
        }
    }

    /** Called by the settings configurable after the user saves a new path. */
    fun onSettingsChanged() {
        val dir = resolveOmpReviewDir()?.toString() ?: return
        LocalFileSystem.getInstance().addRootToWatch(dir, /* watchRecursively= */ true)
        ApplicationManager.getApplication().executeOnPooledThread { reload() }
    }

    /** Parent of the .omp-review directory — the actual git repo root. */
    fun repoRoot(): File? = resolveOmpReviewDir()?.parent?.toFile()

    /** Persist updated comments to review.md (or delete it once no comments remain) and broadcast. */
    fun save(state: ReviewState) {
        val dir = resolveOmpReviewDir() ?: return
        val reviewFile = dir.resolve("review.md")

        if (state.files.none { it.comments.isNotEmpty() }) {
            Files.deleteIfExists(reviewFile)
        } else {
            Files.createDirectories(dir)
            reviewFile.writeText(ReviewMarkdown.render(state))
        }
        update(state)
    }

    private fun update(state: ReviewState?) {
        currentState = state
        ApplicationManager.getApplication().invokeLater {
            project.messageBus.syncPublisher(TOPIC).stateChanged(state)
        }
    }
}

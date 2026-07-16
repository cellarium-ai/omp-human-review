package io.github.ompreview.startup

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import io.github.ompreview.services.OmpReviewProjectService

class OmpReviewStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = project.service<OmpReviewProjectService>()

        // Register the initial watch. resolveOmpReviewDir() already respects settings,
        // so this is correct even when a custom path is configured.
        service.resolveOmpReviewDir()?.toString()?.let { dir ->
            LocalFileSystem.getInstance().addRootToWatch(dir, /* watchRecursively= */ true)
        }

        project.messageBus.connect().subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    // Re-resolve on every batch so settings changes take effect without restart.
                    val currentDir = service.resolveOmpReviewDir()?.toString() ?: return

                    // When .omp-review/ is created (after deletion), the old watch was
                    // invalidated. Re-register so subsequent writes are detected, then reload.
                    if (events.any { it is VFileCreateEvent && it.path == currentDir }) {
                        LocalFileSystem.getInstance()
                            .addRootToWatch(currentDir, /* watchRecursively= */ true)
                        ApplicationManager.getApplication().executeOnPooledThread {
                            service.reload()
                        }
                        return
                    }

                    // Normal case: review.md written by the plugin or removed by OMP after delivery.
                    if (events.any { it.path == "$currentDir/review.md" }) {
                        ApplicationManager.getApplication().executeOnPooledThread {
                            service.reload()
                        }
                    }
                }
            }
        )

        // Eagerly compute the initial diff + load any persisted comments before the
        // toolwindow is first shown.
        ApplicationManager.getApplication().executeOnPooledThread {
            service.reload()
        }
    }
}

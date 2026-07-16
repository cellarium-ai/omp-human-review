package io.github.ompreview.toolWindow

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.EditorCustomElementRenderer
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.Inlay
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeRegistry
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.content.ContentFactory
import io.github.ompreview.model.CommentCategory
import io.github.ompreview.model.ReviewComment
import io.github.ompreview.model.ReviewFile
import io.github.ompreview.model.ReviewState
import io.github.ompreview.model.ReviewStatus
import io.github.ompreview.services.OmpReviewProjectService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Rectangle
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.util.UUID
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.ButtonGroup
import javax.swing.JRadioButton

class OmpReviewToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = OmpReviewPanel(project, toolWindow.disposable)
        val content = ContentFactory.getInstance().createContent(panel.component, null, false)
        toolWindow.contentManager.addContent(content)
    }
    override fun shouldBeAvailable(project: Project) = true
}

class OmpReviewPanel(private val project: Project, private val parentDisposable: Disposable) {

    // ── File list (left) ──────────────────────────────────────────────────────

    private val listModel = DefaultListModel<ReviewFile>()
    private var suppressFileSelection = false

    private val fileList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = ReviewFileCellRenderer()
        addListSelectionListener { e ->
            if (!e.valueIsAdjusting && !suppressFileSelection) onFileSelected(selectedValue)
        }
    }

    // ── Diff panel ────────────────────────────────────────────────────────────

    private val diffPanel: DiffRequestPanel by lazy {
        DiffManager.getInstance().createRequestPanel(project, parentDisposable, null)
    }

    // ── Comment list (bottom-right) ───────────────────────────────────────────

    private val commentListModel = DefaultListModel<ReviewComment>()
    private val commentList = JBList(commentListModel).apply {
        cellRenderer = CommentListRenderer()
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private val refreshButton = JButton("Refresh").apply {
        addActionListener {
            ApplicationManager.getApplication().executeOnPooledThread {
                project.service<OmpReviewProjectService>().reload()
            }
        }
    }

    private val addCommentButton = JButton("Add Comment").apply {
        isEnabled = false
        addActionListener { addComment() }
    }

    // Enabled when a file is selected — no line selection needed.
    private val addFileCommentButton = JButton("Add File Comment").apply {
        isEnabled = false
        addActionListener { addFileComment() }
    }

    // Enabled when a comment is selected in the comment list.
    private val deleteCommentButton = JButton("Delete Comment").apply {
        isEnabled = false
        addActionListener { deleteSelectedComment() }
    }

    private val submitButton = JButton("Submit Review").apply {
        isEnabled = false
        addActionListener { submitReview() }
    }

    // ── Main splitter ─────────────────────────────────────────────────────────

    private val splitter = OnePixelSplitter(false, 0.25f).apply {
        firstComponent = JBScrollPane(fileList)
        secondComponent = emptyRightPanel()
    }

    // ── Root component ────────────────────────────────────────────────────────

    val component: JBPanel<*> = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(
            JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.RIGHT, 6, 4)).apply {
                add(refreshButton)
                add(addCommentButton)
                add(addFileCommentButton)
                add(deleteCommentButton)
                add(submitButton)
            },
            BorderLayout.NORTH,
        )
        add(splitter, BorderLayout.CENTER)
    }

    // ── Mutable state ─────────────────────────────────────────────────────────

    private var selectedFile: ReviewFile? = null
    private var rightEditor: EditorEx? = null

    // Keyed by comment ID so we can dispose exactly the right inlay on delete.
    private val activeInlays = mutableMapOf<String, Inlay<*>>()

    // ── Init ──────────────────────────────────────────────────────────────────

    init {
        project.messageBus
            .connect(parentDisposable)
            .subscribe(OmpReviewProjectService.TOPIC, object : OmpReviewProjectService.Listener {
                override fun stateChanged(state: ReviewState?) = updateFromState(state)
            })

        // Enable/disable Delete Comment button from comment list selection.
        commentList.addListSelectionListener {
            deleteCommentButton.isEnabled = !commentList.isSelectionEmpty
        }

        // Delete key + Escape on the comment list.
        commentList.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_DELETE -> deleteSelectedComment()
                    KeyEvent.VK_ESCAPE -> commentList.clearSelection()
                }
            }
        })

        // Clear selection when focus leaves the list — but not when it moves to
        // the Delete button, which needs the selection live when its action fires.
        commentList.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) {
                if (e.oppositeComponent != deleteCommentButton) {
                    commentList.clearSelection()
                }
            }
        })

        updateFromState(project.service<OmpReviewProjectService>().currentState)
    }

    // ── State → UI ────────────────────────────────────────────────────────────

    private fun updateFromState(state: ReviewState?) {
        val prevFile = selectedFile?.file

        suppressFileSelection = true
        listModel.clear()
        state?.files?.forEach(listModel::addElement)

        if (prevFile != null) {
            val idx = (0 until listModel.size).indexOfFirst { listModel[it].file == prevFile }
            if (idx >= 0) fileList.selectedIndex = idx
        }
        suppressFileSelection = false

        selectedFile = state?.files?.find { it.file == prevFile }
        submitButton.isEnabled = state?.files?.any { it.comments.isNotEmpty() } == true
    }

    // ── File selection → diff viewer ──────────────────────────────────────────

    // Reclaim the diff viewer's vertical space when there is nothing to comment
    // on — an empty comment list otherwise renders IntelliJ's default "Nothing
    // to show" placeholder, wasting roughly a quarter of the right panel's height.
    private fun layoutRightPanel(hasComments: Boolean) {
        splitter.secondComponent = if (hasComments) {
            OnePixelSplitter(true, 0.75f).apply {
                firstComponent  = diffPanel.component
                secondComponent = JBScrollPane(commentList)
            }
        } else {
            diffPanel.component
        }
    }

    private fun onFileSelected(file: ReviewFile?) {
        if (file == null) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val beforeText = fetchFromGit(file.file) ?: ""
            val afterText  = readFromDisk(file.file) ?: ""
            val fileType   = FileTypeRegistry.getInstance()
                .getFileTypeByFileName(file.file.substringAfterLast('/'))

            val request = SimpleDiffRequest(
                file.file,
                DiffContentFactory.getInstance().create(project, beforeText, fileType),
                DiffContentFactory.getInstance().create(project, afterText,  fileType),
                "Before (HEAD)",
                "After",
            )

            ApplicationManager.getApplication().invokeLater {
                activeInlays.values.forEach { it.dispose() }
                activeInlays.clear()

                layoutRightPanel(file.comments.isNotEmpty())

                diffPanel.setRequest(request)
                addFileCommentButton.isEnabled = true
                selectedFile = file

                ApplicationManager.getApplication().invokeLater {
                    addCommentButton.isEnabled = false

                    // Find all editors inside the diff panel and apply syntax highlighting.
                    // Each editor needs its own highlighter instance (they are stateful).
                    val allDiffEditors = EditorFactory.getInstance().allEditors
                        .filterIsInstance<EditorEx>()
                        .filter {
                            javax.swing.SwingUtilities.isDescendingFrom(
                                it.component, diffPanel.component
                            )
                        }

                    allDiffEditors.forEach { ed ->
                        ed.setHighlighter(
                            EditorHighlighterFactory.getInstance()
                                .createEditorHighlighter(project, fileType)
                        )
                    }

                    val editor = allDiffEditors.lastOrNull()
                    if (editor != null && editor !== rightEditor) {
                        editor.selectionModel.addSelectionListener(
                            object : SelectionListener {
                                override fun selectionChanged(e: SelectionEvent) {
                                    addCommentButton.isEnabled =
                                        editor.selectionModel.hasSelection()
                                }
                            },
                            parentDisposable,
                        )
                        rightEditor = editor
                    }
                    renderInlays(file, rightEditor)
                    commentListModel.clear()
                    file.comments.forEach(commentListModel::addElement)
                }
            }
        }
    }

    // ── Add comment ───────────────────────────────────────────────────────────

    private fun addComment() {
        val editor = rightEditor  ?: return
        val file   = selectedFile ?: return

        val doc       = editor.document
        val startLine = doc.getLineNumber(editor.selectionModel.selectionStart) + 1
        val endLine   = doc.getLineNumber(editor.selectionModel.selectionEnd)   + 1
        val label     = if (startLine == endLine) "Line $startLine"
                        else                      "Lines $startLine\u2013$endLine"

        val dialog = CommentInputDialog(
            project,
            "Add Review Comment",
            "Comment on $label in ${file.file.substringAfterLast('/')}:",
        )
        if (!dialog.showAndGet()) return
        val body = dialog.body

        val comment = ReviewComment(
            id       = UUID.randomUUID().toString(),
            line     = startLine,
            lineEnd  = if (endLine > startLine) endLine else null,
            category = dialog.category,
            body     = body,
        )

        val service = project.service<OmpReviewProjectService>()
        val state   = service.currentState ?: return
        val updatedFiles = state.files.map { f ->
            if (f.file == file.file) f.copy(comments = f.comments + comment) else f
        }
        service.save(state.copy(files = updatedFiles))
        selectedFile = updatedFiles.first { it.file == file.file }
        renderInlays(selectedFile!!, editor)
        commentListModel.addElement(comment)
        layoutRightPanel(true)
    }

    // ── Add file-level comment ────────────────────────────────────────────────

    private fun addFileComment() {
        val file = selectedFile ?: return

        val dialog = CommentInputDialog(
            project,
            "Add File Comment",
            "File comment on ${file.file.substringAfterLast('/')}:",
        )
        if (!dialog.showAndGet()) return
        val body = dialog.body

        val comment = ReviewComment(
            id       = UUID.randomUUID().toString(),
            line     = null,
            category = dialog.category,
            body     = body,
        )

        val service = project.service<OmpReviewProjectService>()
        val state   = service.currentState ?: return
        val updatedFiles = state.files.map { f ->
            if (f.file == file.file) f.copy(comments = f.comments + comment) else f
        }
        service.save(state.copy(files = updatedFiles))
        selectedFile = updatedFiles.first { it.file == file.file }
        renderInlays(selectedFile!!, rightEditor)
        commentListModel.addElement(comment)
        layoutRightPanel(true)
    }

    // ── Delete comment ────────────────────────────────────────────────────────

    private fun deleteSelectedComment() {
        val comment = commentList.selectedValue ?: return
        val file    = selectedFile ?: return

        // Dispose just this comment's inlay.
        activeInlays.remove(comment.id)?.dispose()

        // Remove from the bottom list.
        commentListModel.removeElement(comment)

        // Persist the deletion.
        val service = project.service<OmpReviewProjectService>()
        val state   = service.currentState ?: return
        val updatedFiles = state.files.map { f ->
            if (f.file == file.file) f.copy(comments = f.comments.filter { it.id != comment.id })
            else f
        }
        service.save(state.copy(files = updatedFiles))
        selectedFile = updatedFiles.first { it.file == file.file }
        layoutRightPanel(commentListModel.size > 0)
    }

    // ── Submit review ─────────────────────────────────────────────────────────

    private fun submitReview() {
        val service = project.service<OmpReviewProjectService>()
        val state   = service.currentState ?: return
        service.save(state.copy(status = ReviewStatus.SUBMITTED))

        activeInlays.values.forEach { it.dispose() }
        activeInlays.clear()
        selectedFile = null
        rightEditor = null
        addCommentButton.isEnabled = false
        addFileCommentButton.isEnabled = false
        deleteCommentButton.isEnabled = false
        submitButton.isEnabled = false
        splitter.secondComponent = emptyRightPanel()
        listModel.clear()
        commentListModel.clear()
        ToolWindowManager.getInstance(project).getToolWindow("Human Review")?.hide()
    }

    // ── Inlay rendering ───────────────────────────────────────────────────────

    private fun renderInlays(file: ReviewFile, editor: EditorEx?) {
        editor ?: return
        activeInlays.values.forEach { it.dispose() }
        activeInlays.clear()
        val doc = editor.document
        for (comment in file.comments) {
            if (comment.line == null) {
                // File-level comment: render above the first line.
                editor.inlayModel
                    .addBlockElement(0, false, true, 0, CommentInlayRenderer(comment))
                    ?.also { activeInlays[comment.id] = it }
            } else {
                val lineIndex = (comment.line - 1).coerceIn(0, doc.lineCount - 1)
                val offset    = doc.getLineEndOffset(lineIndex)
                editor.inlayModel
                    .addBlockElement(offset, false, false, 0, CommentInlayRenderer(comment))
                    ?.also { activeInlays[comment.id] = it }
            }
        }
    }

    // ── Content fetchers ──────────────────────────────────────────────────────

    private fun repoRoot(): File =
        project.service<OmpReviewProjectService>().repoRoot()
            ?: File(project.basePath ?: "")

    private fun fetchFromGit(file: String): String? {
        return try {
            val proc = ProcessBuilder("git", "show", "HEAD:$file")
                .directory(repoRoot())
                .start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            if (proc.exitValue() == 0) out else null
        } catch (_: Exception) {
            null
        }
    }

    private fun readFromDisk(file: String): String? {
        val f = File(repoRoot(), file)
        return if (f.exists()) f.readText() else null
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

// Comments can now contain real newlines (multi-line Add Comment dialog); these single-line
// widgets must never render one, so collapse to a space for display only — the stored body
// keeps its real newlines.
private fun displayBody(body: String): String = body.replace('\n', ' ')

private fun emptyRightPanel(): JBPanel<*> =
    JBPanel<JBPanel<*>>(BorderLayout()).apply {
        add(
            JBLabel("Select a file to review", SwingConstants.CENTER)
                .apply { foreground = foreground.darker() },
            BorderLayout.CENTER,
        )
    }

private class ReviewFileCellRenderer : ColoredListCellRenderer<ReviewFile>() {
    override fun customizeCellRenderer(
        list: JList<out ReviewFile>, value: ReviewFile,
        index: Int, selected: Boolean, hasFocus: Boolean,
    ) {
        append(value.file.substringAfterLast('/'), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
        if (value.comments.isNotEmpty()) {
            append("  ${value.comments.size}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
        toolTipText = value.file
    }
}

private class CommentListRenderer : ColoredListCellRenderer<ReviewComment>() {
    override fun customizeCellRenderer(
        list: JList<out ReviewComment>, value: ReviewComment,
        index: Int, selected: Boolean, hasFocus: Boolean,
    ) {
        val lineLabel = when {
            value.line == null    -> "file"
            value.lineEnd != null -> "${value.line}\u2013${value.lineEnd}"
            else                  -> "${value.line}"
        }
        val catLabel = if (value.category == CommentCategory.QUESTION) "[Q]" else "[R]"
        append("$catLabel $lineLabel  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        append(displayBody(value.body), SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }
}

private class CommentInlayRenderer(private val comment: ReviewComment) : EditorCustomElementRenderer {

    override fun calcWidthInPixels(inlay: Inlay<*>): Int =
        inlay.editor.scrollingModel.visibleArea.width.coerceAtLeast(200)

    override fun calcHeightInPixels(inlay: Inlay<*>): Int = 22

    override fun paint(inlay: Inlay<*>, g: Graphics, r: Rectangle, attrs: TextAttributes) {
        g.color = Color(255, 255, 204)
        g.fillRect(r.x, r.y, r.width, r.height)
        g.color = Color(200, 180, 0)
        g.drawRect(r.x, r.y, r.width - 1, r.height - 1)
        g.color = Color(80, 60, 0)
        g.font = g.font.deriveFont(12f)
        val catLabel = if (comment.category == CommentCategory.QUESTION) "[Q]" else "[R]"
        g.drawString("\uD83D\uDCAC  $catLabel ${displayBody(comment.body)}", r.x + 8, r.y + 15)
    }
}

// Replaces Messages.showMultilineInputDialog: its internal JTextArea
// (MessageMultilineInputDialog.createTextFieldComponent) never enables
// setLineWrap/setWrapStyleWord, so long text grows the dialog sideways
// instead of wrapping to the next line — the 7 rows of height sit unused.
// Neither flag is exposed on the public API, so we own the JTextArea instead.
private class CommentInputDialog(
    project: Project,
    dialogTitle: String,
    private val message: String,
) : DialogWrapper(project, true) {

    private val textArea = JBTextArea(7, 50).apply {
        lineWrap = true
        wrapStyleWord = true
    }

    private val requestButton  = JRadioButton("Request").apply { isSelected = true }
    private val questionButton = JRadioButton("Question")
    // categoryGroup must be an instance field — ButtonGroup keeps mutual-exclusion state.
    @Suppress("unused")
    private val categoryGroup  = ButtonGroup().also { it.add(requestButton); it.add(questionButton) }

    init {
        setTitle(dialogTitle)
        init()
    }

    override fun createCenterPanel(): JComponent {
        val radioRow = JBPanel<JBPanel<*>>(FlowLayout(FlowLayout.LEFT, 0, 4)).apply {
            add(requestButton)
            add(questionButton)
        }
        return JBPanel<JBPanel<*>>(BorderLayout()).apply {
            add(JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(JBLabel(message), BorderLayout.NORTH)
                add(radioRow, BorderLayout.SOUTH)
            }, BorderLayout.NORTH)
            add(JBScrollPane(textArea), BorderLayout.CENTER)
        }
    }

    override fun getPreferredFocusedComponent(): JComponent = textArea

    val body: String get() = textArea.text.trim()
    val category: CommentCategory
        get() = if (questionButton.isSelected) CommentCategory.QUESTION else CommentCategory.REQUEST
}

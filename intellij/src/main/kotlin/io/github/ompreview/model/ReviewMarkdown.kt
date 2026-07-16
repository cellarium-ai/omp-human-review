package io.github.ompreview.model

// Mirrors the FILE_HEADER / COMMENT_LINE format in omp/src/index.ts — keep both in sync.
//
//   ---
//   status: open|submitted
//   ---
//
//   ## <file>
//
//   - id:<uuid> line:<n>[-<m>] <body>
//   - id:<uuid> <body>                ← file-level (no line: token)

private val FILE_HEADER = Regex("^## (.+)$")
private val COMMENT_LINE = Regex("""^- id:([0-9a-fA-F-]+)(?: line:(\d+)(?:-(\d+))?)?(?: cat:([QR]))? (.*)$""")

// Preserves multi-line comment bodies (the Add Comment / Add File Comment dialogs use a
// multi-line text area, Step 5) on a single physical review.md line: backslash -> "\\",
// a real newline -> the two characters "\n". Mirrors unescapeBody in omp/src/index.ts —
// TS only ever reads this file, so it implements the reverse direction only.
private fun escapeBody(body: String): String {
    val sb = StringBuilder()
    for (c in body.replace("\r\n", "\n").replace('\r', '\n')) {
        when (c) {
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            else -> sb.append(c)
        }
    }
    return sb.toString()
}

private fun unescapeBody(raw: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < raw.length) {
        val c = raw[i]
        if (c == '\\' && i + 1 < raw.length) {
            when (raw[i + 1]) {
                'n'  -> { sb.append('\n'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                else -> { sb.append(c); i += 1 }
            }
        } else {
            sb.append(c); i += 1
        }
    }
    return sb.toString()
}

object ReviewMarkdown {

    fun parse(text: String): ReviewState {
        val lines = text.lines()
        if (lines.size < 3 || lines[0].trim() != "---") return ReviewState()

        val status = when (lines[1].trim()) {
            "status: submitted" -> ReviewStatus.SUBMITTED
            "status: open"      -> ReviewStatus.OPEN
            else                -> return ReviewState()
        }
        if (lines[2].trim() != "---") return ReviewState()

        val files = mutableListOf<ReviewFile>()
        var currentFile: String? = null
        var currentComments = mutableListOf<ReviewComment>()

        fun flush() {
            val file = currentFile ?: return
            if (currentComments.isNotEmpty()) files += ReviewFile(file, currentComments.toList())
        }

        for (line in lines.drop(3)) {
            val header = FILE_HEADER.matchEntire(line)
            if (header != null) {
                flush()
                currentFile = header.groupValues[1]
                currentComments = mutableListOf()
                continue
            }
            val match = COMMENT_LINE.matchEntire(line) ?: continue
            currentComments += ReviewComment(
                id       = match.groupValues[1],
                line     = match.groupValues[2].toIntOrNull(),
                lineEnd  = match.groupValues[3].toIntOrNull(),
                category = when (match.groupValues[4]) {
                    "Q"  -> CommentCategory.QUESTION
                    "R"  -> CommentCategory.REQUEST
                    else -> CommentCategory.REQUEST
                },
                body     = unescapeBody(match.groupValues[5]),
            )
        }
        flush()

        return ReviewState(status, files)
    }

    fun render(state: ReviewState): String {
        val sb = StringBuilder()
        sb.append("---\n")
        sb.append("status: ${if (state.status == ReviewStatus.SUBMITTED) "submitted" else "open"}\n")
        sb.append("---\n")

        for (file in state.files) {
            if (file.comments.isEmpty()) continue
            sb.append("\n## ${file.file}\n\n")
            for (comment in file.comments) {
                val lineToken = when {
                    comment.line == null    -> ""
                    comment.lineEnd != null -> " line:${comment.line}-${comment.lineEnd}"
                    else                    -> " line:${comment.line}"
                }
                val catToken = when (comment.category) {
                    CommentCategory.QUESTION -> " cat:Q"
                    CommentCategory.REQUEST  -> " cat:R"
                }
                val body = escapeBody(comment.body)
                sb.append("- id:${comment.id}$lineToken$catToken $body\n")
            }
        }
        return sb.toString()
    }
}

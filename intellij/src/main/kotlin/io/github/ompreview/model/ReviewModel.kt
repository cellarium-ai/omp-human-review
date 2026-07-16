package io.github.ompreview.model

data class ReviewComment(
    val id: String,
    val line: Int? = null,
    val lineEnd: Int? = null,
    val category: CommentCategory = CommentCategory.REQUEST,
    val body: String,
)

data class ReviewFile(
    val file: String,
    val comments: List<ReviewComment> = emptyList(),
)

enum class ReviewStatus {
    OPEN,
    SUBMITTED,
}

enum class CommentCategory {
    QUESTION,
    REQUEST,
}

data class ReviewState(
    val status: ReviewStatus = ReviewStatus.OPEN,
    val files: List<ReviewFile> = emptyList(),
)

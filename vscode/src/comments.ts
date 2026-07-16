import * as vscode from "vscode";
import { ReviewComment, ReviewState } from "./model/reviewModel";
import { toDiffUri } from "./contentProvider";

class ReviewVscodeComment implements vscode.Comment {
  constructor(
    public body: string,
    public mode: vscode.CommentMode,
    public author: vscode.CommentAuthorInformation,
    public parent: vscode.CommentThread,
  ) {}
}

export class CommentsManager implements vscode.Disposable {
  readonly controller = vscode.comments.createCommentController("ompReview", "Human Review");
  private readonly threads = new Map<string, vscode.CommentThread[]>(); // file -> threads
  private readonly commentIdByThread = new WeakMap<vscode.CommentThread, string>();

  constructor() {
    this.controller.commentingRangeProvider = {
      provideCommentingRanges: (document: vscode.TextDocument) => {
        if (document.uri.scheme !== "omp-review") return null;
        if (!document.uri.query.includes("side=after")) return null;
        return [new vscode.Range(0, 0, Math.max(document.lineCount - 1, 0), 0)];
      },
    };
  }

  dispose(): void { this.controller.dispose(); }

  getCommentId(thread: vscode.CommentThread): string | undefined { return this.commentIdByThread.get(thread); }

  rebuild(state: ReviewState | undefined): void {
    for (const threads of this.threads.values()) for (const t of threads) t.dispose();
    this.threads.clear();
    if (!state) return;
    for (const file of state.files) {
      this.threads.set(file.file, file.comments.map(c => this.createThread(file.file, c)));
    }
  }

  private createThread(file: string, comment: ReviewComment): vscode.CommentThread {
    const range = comment.line == null
      ? new vscode.Range(0, 0, 0, 0)
      : new vscode.Range(comment.line - 1, 0, (comment.lineEnd ?? comment.line) - 1, 0);
    const thread = this.controller.createCommentThread(toDiffUri(file, "after"), range, []);
    const catLabel = comment.category === "Q" ? "[Q]" : "[R]";
    thread.label = comment.line == null
      ? `${catLabel} File comment`
      : comment.lineEnd
        ? `${catLabel} Lines ${comment.line}\u2013${comment.lineEnd}`
        : `${catLabel} Line ${comment.line}`;
    thread.canReply = false; // no edit/reply — delete + recreate only, matches IntelliJ (no edit feature)
    thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
    thread.comments = [new ReviewVscodeComment(comment.body, vscode.CommentMode.Preview, { name: "Reviewer" }, thread)];
    this.commentIdByThread.set(thread, comment.id);
    return thread;
  }

  /** Used only by the "Add File Comment" command (step 8) to open a draft input box with no gutter click. */
  startFileComment(file: string): vscode.CommentThread {
    const thread = this.controller.createCommentThread(toDiffUri(file, "after"), new vscode.Range(0, 0, 0, 0), []);
    thread.contextValue = "pending-file";
    thread.collapsibleState = vscode.CommentThreadCollapsibleState.Expanded;
    return thread;
  }
}

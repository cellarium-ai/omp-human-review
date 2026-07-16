import * as vscode from "vscode";
import { randomUUID } from "node:crypto";
import { basename, join, sep } from "node:path";
import { ReviewFile, ReviewState } from "./model/reviewModel";
import { ReviewService } from "./reviewService";
import { OMP_REVIEW_SCHEME, OmpReviewContentProvider, fileOf, toDiffUri } from "./contentProvider";
import { ReviewFileTreeProvider } from "./fileTree";
import { CommentsManager } from "./comments";

export async function activate(context: vscode.ExtensionContext): Promise<void> {
  const folder = vscode.workspace.workspaceFolders?.[0];
  if (!folder) return; // no workspace open — mirrors `project.basePath == null` short-circuit

  const service = new ReviewService(folder);
  const contentProvider = new OmpReviewContentProvider(() => service.repoRoot());
  const commentsManager = new CommentsManager();
  const fileTreeProvider = new ReviewFileTreeProvider();

  context.subscriptions.push(
    service,
    commentsManager,
    vscode.workspace.registerTextDocumentContentProvider(OMP_REVIEW_SCHEME, contentProvider),
    vscode.window.createTreeView("ompReviewFiles", { treeDataProvider: fileTreeProvider }),
  );

  const setHasComments = (state: ReviewState | undefined) =>
    vscode.commands.executeCommand("setContext", "ompReview.hasComments", state?.files.some(f => f.comments.length > 0) ?? false);

  // Render nothing once submitted — otherwise the watcher's post-write reload (or an
  // external writer, e.g. IntelliJ, setting status:submitted) would repopulate the tree a
  // few ms after ompReview.submitReview clears it. The persisted file keeps its comments
  // until the next OMP run deletes it; only the view is suppressed.
  context.subscriptions.push(service.onDidChangeState(state => {
    const displayState = state?.status === "submitted" ? undefined : state;
    fileTreeProvider.setFiles(displayState?.files ?? []);
    commentsManager.rebuild(displayState);
    setHasComments(displayState);
  }));

  context.subscriptions.push(
    vscode.commands.registerCommand("ompReview.refresh", () => service.reload()),

    vscode.commands.registerCommand("ompReview.openDiff", async (file: ReviewFile) => {
      const before = toDiffUri(file.file, "before");
      const after = toDiffUri(file.file, "after");
      await vscode.commands.executeCommand("vscode.diff", before, after, `${basename(file.file)} (Before \u2194 After)`, { preview: true });
    }),

    vscode.commands.registerCommand("ompReview.addFileComment", async (item: ReviewFile) => {
      await vscode.commands.executeCommand("ompReview.openDiff", item);
      commentsManager.startFileComment(item.file);
    }),

    vscode.commands.registerCommand("ompReview.createComment", async (reply: vscode.CommentReply) => {
      const thread = reply.thread;
      const body = reply.text.trim();
      if (!body) { thread.dispose(); return; }
      const state = service.currentState;
      if (!state) { thread.dispose(); return; }

      const categoryPick = await vscode.window.showQuickPick(
        [
          { label: "Request", description: "Model should implement this change", value: "R" as const },
          { label: "Question", description: "Model should answer only — no code changes", value: "Q" as const },
        ],
        { title: "Comment type", placeHolder: "Request (default)" },
      );
      if (!categoryPick) { thread.dispose(); return; }

      const isFileLevel = thread.contextValue === "pending-file";
      const range = thread.range ?? new vscode.Range(0, 0, 0, 0);
      const startLine = range.start.line + 1;
      const endLine = range.end.line + 1;
      const comment = {
        id: randomUUID(),
        line: isFileLevel ? undefined : startLine,
        lineEnd: !isFileLevel && endLine > startLine ? endLine : undefined,
        category: categoryPick.value,
        body,
      };
      const file = fileOf(thread.uri);
      thread.dispose(); // drop the transient draft; save()'s state-change rebuild recreates the canonical thread
      const updatedFiles = state.files.map(f => f.file === file ? { ...f, comments: [...f.comments, comment] } : f);
      await service.save({ ...state, files: updatedFiles });
    }),

    vscode.commands.registerCommand("ompReview.deleteThread", async (thread: vscode.CommentThread) => {
      const state = service.currentState;
      if (!state) return;
      const commentId = commentsManager.getCommentId(thread);
      const file = fileOf(thread.uri);
      thread.dispose();
      if (!commentId) return;
      const updatedFiles = state.files.map(f => f.file === file ? { ...f, comments: f.comments.filter(c => c.id !== commentId) } : f);
      await service.save({ ...state, files: updatedFiles });
    }),

    vscode.commands.registerCommand("ompReview.submitReview", async () => {
      const state = service.currentState;
      if (!state) return;
      // save() fires onDidChangeState with status:"submitted"; the listener above already
      // renders that as an empty view, so no separate clear step is needed here.
      await service.save({ ...state, status: "submitted" });
    }),
  );

  let watcher = createReviewWatcher(service);
  context.subscriptions.push({ dispose: () => watcher.dispose() });
  context.subscriptions.push(vscode.workspace.onDidChangeConfiguration(e => {
    if (e.affectsConfiguration("ompReview.reviewDirectory")) {
      watcher.dispose();
      watcher = createReviewWatcher(service);
      service.reload();
    }
  }));

  await service.reload();
}

function createReviewWatcher(service: ReviewService): vscode.FileSystemWatcher {
  // Base the pattern at repoRoot (always exists) rather than at the .omp-review dir itself
  // (which may not exist yet) — sidesteps the root-deleted-and-recreated edge case that
  // OmpReviewStartupActivity.kt:32-41 has to special-case for IntelliJ's VFS watch roots.
  const root = service.repoRoot();
  const relativeReviewFile = join(service.resolveReviewDir(), "review.md").slice(root.length + 1).split(sep).join("/");
  const watcher = vscode.workspace.createFileSystemWatcher(new vscode.RelativePattern(root, relativeReviewFile));
  const reload = () => { if (!service.consumeSelfWrite()) service.reload(); };
  watcher.onDidCreate(reload);
  watcher.onDidChange(reload);
  watcher.onDidDelete(reload);
  return watcher;
}

export function deactivate(): void {}

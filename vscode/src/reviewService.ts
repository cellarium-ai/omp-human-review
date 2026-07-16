import * as vscode from "vscode";
import { existsSync, promises as fsp } from "node:fs";
import { dirname, isAbsolute, join } from "node:path";
import { ReviewFile, ReviewState } from "./model/reviewModel";
import { parseReviewMarkdown, renderReviewMarkdown } from "./model/reviewMarkdown";
import { computeChangedFiles } from "./git";

export class ReviewService implements vscode.Disposable {
  private state: ReviewState | undefined;
  private selfWritePending = false;
  private readonly emitter = new vscode.EventEmitter<ReviewState | undefined>();
  readonly onDidChangeState = this.emitter.event;

  constructor(private readonly folder: vscode.WorkspaceFolder) {}

  get currentState(): ReviewState | undefined { return this.state; }

  resolveReviewDir(): string {
    const base = this.folder.uri.fsPath;
    const override = vscode.workspace.getConfiguration("ompReview").get<string>("reviewDirectory", "").trim();
    if (!override) return join(base, ".omp-review");
    return isAbsolute(override) ? override : join(base, override);
  }

  repoRoot(): string { return dirname(this.resolveReviewDir()); }

  async reload(): Promise<void> {
    const dir = this.resolveReviewDir();
    const reviewFile = join(dir, "review.md");
    let persisted: ReviewState = { status: "open", files: [] };
    try { if (existsSync(reviewFile)) persisted = parseReviewMarkdown(await fsp.readFile(reviewFile, "utf8")); }
    catch { /* non-fatal, matches OmpReviewProjectService.kt:44 */ }

    const changedFiles = await computeChangedFiles(this.repoRoot());
    const commentsByFile = new Map(persisted.files.map(f => [f.file, f.comments]));
    const files: ReviewFile[] = changedFiles.map(file => ({ file, comments: commentsByFile.get(file) ?? [] }));
    this.update({ status: persisted.status, files });
  }

  async save(state: ReviewState): Promise<void> {
    const dir = this.resolveReviewDir();
    const reviewFile = join(dir, "review.md");
    this.selfWritePending = true;
    if (!state.files.some(f => f.comments.length > 0)) {
      await fsp.rm(reviewFile, { force: true });
    } else {
      await fsp.mkdir(dir, { recursive: true });
      await fsp.writeFile(reviewFile, renderReviewMarkdown(state), "utf8");
    }
    this.update(state);
  }

  /**
   * Consumes the self-write flag set by save(). The file watcher (extension.ts) calls this
   * on each fs event and skips its reload() when true — otherwise every add/delete comment
   * triggers a redundant git diff via the watcher noticing our own write.
   */
  consumeSelfWrite(): boolean {
    const pending = this.selfWritePending;
    this.selfWritePending = false;
    return pending;
  }

  dispose(): void { this.emitter.dispose(); }

  private update(state: ReviewState | undefined): void {
    this.state = state;
    this.emitter.fire(state);
  }
}

import * as vscode from "vscode";
import { basename } from "node:path";
import { ReviewFile } from "./model/reviewModel";

export class ReviewFileTreeProvider implements vscode.TreeDataProvider<ReviewFile> {
  private readonly emitter = new vscode.EventEmitter<void>();
  readonly onDidChangeTreeData = this.emitter.event;
  private files: ReviewFile[] = [];

  setFiles(files: ReviewFile[]): void { this.files = files; this.emitter.fire(); }

  getTreeItem(element: ReviewFile): vscode.TreeItem {
    const item = new vscode.TreeItem(basename(element.file), vscode.TreeItemCollapsibleState.None);
    item.description = element.comments.length > 0 ? String(element.comments.length) : undefined;
    item.tooltip = element.file;
    item.contextValue = "ompReviewFile";
    item.command = { command: "ompReview.openDiff", title: "Open Diff", arguments: [element] };
    return item;
  }

  getChildren(element?: ReviewFile): ReviewFile[] { return element ? [] : this.files; }
}

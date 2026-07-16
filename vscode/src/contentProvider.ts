import * as vscode from "vscode";
import { fetchFromGit, readFromDisk } from "./git";

export const OMP_REVIEW_SCHEME = "omp-review";
export type DiffSide = "before" | "after";

export function toDiffUri(file: string, side: DiffSide): vscode.Uri {
  return vscode.Uri.from({ scheme: OMP_REVIEW_SCHEME, path: "/" + file, query: `side=${side}` });
}

export function sideOf(uri: vscode.Uri): DiffSide | undefined {
  if (uri.scheme !== OMP_REVIEW_SCHEME) return undefined;
  const m = /(?:^|&)side=(before|after)(?:&|$)/.exec(uri.query);
  return (m?.[1] as DiffSide | undefined);
}

export function fileOf(uri: vscode.Uri): string { return uri.path.replace(/^\//, ""); }

export class OmpReviewContentProvider implements vscode.TextDocumentContentProvider {
  constructor(private readonly getRepoRoot: () => string) {}
  async provideTextDocumentContent(uri: vscode.Uri): Promise<string> {
    const root = this.getRepoRoot();
    const file = fileOf(uri);
    return sideOf(uri) === "before" ? fetchFromGit(root, file) : readFromDisk(root, file);
  }
}

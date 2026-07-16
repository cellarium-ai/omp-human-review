import { execFile } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { promisify } from "node:util";

const execFileAsync = promisify(execFile);

async function runGit(cwd: string, args: string[]): Promise<string[]> {
  try {
    const { stdout } = await execFileAsync("git", args, { cwd, maxBuffer: 64 * 1024 * 1024 });
    return stdout.split("\n").map(s => s.trim()).filter(Boolean);
  } catch {
    return [];
  }
}

export async function computeChangedFiles(root: string): Promise<string[]> {
  const [tracked, untracked] = await Promise.all([
    runGit(root, ["diff", "--name-only", "HEAD"]),
    runGit(root, ["ls-files", "--others", "--exclude-standard"]),
  ]);
  return Array.from(new Set([...tracked, ...untracked]));
}

export async function fetchFromGit(root: string, file: string): Promise<string> {
  try {
    const { stdout } = await execFileAsync("git", ["show", `HEAD:${file}`], { cwd: root, maxBuffer: 64 * 1024 * 1024 });
    return stdout;
  } catch {
    return "";
  }
}

export function readFromDisk(root: string, file: string): string {
  try { return readFileSync(join(root, file), "utf8"); } catch { return ""; }
}

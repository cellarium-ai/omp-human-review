import { test } from "node:test";
import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { computeChangedFiles, fetchFromGit, readFromDisk } from "./git";

test("computeChangedFiles lists a modified tracked file + an untracked file; fetchFromGit reads HEAD content", async () => {
  const root = mkdtempSync(join(tmpdir(), "omp-review-git-test-"));
  const run = (args: string[]) => execFileSync("git", args, { cwd: root });
  run(["init", "-q"]);
  run(["config", "user.email", "test@example.com"]);
  run(["config", "user.name", "Test"]);
  writeFileSync(join(root, "tracked.txt"), "original\n");
  run(["add", "tracked.txt"]);
  run(["commit", "-q", "-m", "initial"]);

  writeFileSync(join(root, "tracked.txt"), "edited\n");
  writeFileSync(join(root, "untracked.txt"), "new\n");

  const changed = await computeChangedFiles(root);
  assert.deepEqual(new Set(changed), new Set(["tracked.txt", "untracked.txt"]));

  assert.equal(await fetchFromGit(root, "tracked.txt"), "original\n");
  assert.equal(readFromDisk(root, "tracked.txt"), "edited\n");
  assert.equal(readFromDisk(root, "missing.txt"), "");
});

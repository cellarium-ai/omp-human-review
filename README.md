# omp-review

Code review tooling for [Oh My Pi](https://ohmy.pi) agent runs — a three-part system:

| Part | Directory | Language | Distributed via |
|---|---|---|---|
| OMP extension | `omp/` | TypeScript | `omp plugin link` (see below) |
| IntelliJ plugin | `intellij/` | Kotlin | local `.zip` install |
| VSCode extension | `vscode/` | TypeScript | local `.vsix` install |

All three sides speak the same contract: **`.omp-review/review.md`** in your project root —
a small markdown file with YAML-style frontmatter.

## How it works

```
You click Refresh in the Human Review panel (IntelliJ) or run the Refresh command (VSCode)
  └─ the extension runs git diff HEAD and shows per-file before/after diff
       └─ you annotate lines with comments
            └─ extension writes .omp-review/review.md
                 └─ you click/run Submit Review (status: submitted)
                      └─ next OMP run reads + deletes .omp-review/review.md, injecting comments as context
                           └─ repeat
```

The IntelliJ plugin and VSCode extension are independent, interchangeable front-ends to the
same `.omp-review/review.md` contract — use whichever IDE you're already in.

## Installation

### IDE plugins

**IntelliJ (PyCharm / IDEA / etc.)** — run `./gradlew buildPlugin` inside `intellij/`, then go to
*Settings → Plugins → ⚙ → Install Plugin from Disk…* and select the `.zip` under `intellij/build/distributions/`.

**VS Code** — run `npm install && npm run package` inside `vscode/`, then run
`code --install-extension omp-review-vscode-*.vsix` or use *Extensions → … → Install from VSIX…*.

### OMP extension

```bash
curl -fsSL https://raw.githubusercontent.com/cellarium-ai/omp-human-review/main/omp/install.sh | bash
```

Or manually:

```bash
git clone https://github.com/cellarium-ai/omp-human-review.git ~/.omp-review
omp plugin link ~/.omp-review/omp
```

Restart OMP. The extension activates automatically on the next agent run.
To update later: re-run the script, or `git -C ~/.omp-review pull`.

## Development

- Markdown format changes → update `FILE_HEADER`/`COMMENT_LINE` and the parse/render functions in all three: `omp/src/index.ts`, `intellij/.../model/ReviewMarkdown.kt`, and `vscode/src/model/reviewMarkdown.ts`.
- Run `./gradlew runIde` from `intellij/` to hot-reload the plugin during development.

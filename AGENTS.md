# Repository Guidelines

## Project Overview

**omp-review** is a human-in-the-loop code review bridge for [Oh My Pi (OMP)](https://github.com/oh-my-pi) AI agent runs. The write-side UI (IntelliJ plugin, and the VSCode extension) computes the working-tree diff on demand (a Refresh button/command), lets the developer annotate lines with comments, and writes them to `.omp-review/review.md`. On the next agent run, an OMP TypeScript extension reads that file, injects unresolved comments into the agent context, and deletes it.

Three completely independent build systems live under one git repo — no monorepo tooling:

| Component | Language | Directory |
|---|---|---|
| OMP extension | TypeScript | `omp/` |
| IntelliJ plugin | Kotlin | `intellij/` |
| VSCode extension | TypeScript | `vscode/` |

---

## Architecture & Data Flow

### Diff refresh (IDE-only)

```
User clicks Refresh
  → OmpReviewProjectService.reload(): git diff --name-only HEAD ∪ git ls-files --others --exclude-standard (no staging)
  → merge with persisted comments from .omp-review/review.md
  → messageBus.syncPublisher(TOPIC).stateChanged(state)
  → OmpReviewPanel.updateFromState(): rebuild list models
```

### Write path (IDE → disk)

```
User selects file → diffPanel shows before/after (git show HEAD:<file> vs disk)
User selects text + clicks "Add Comment"
  → ReviewComment(UUID, line, body)
  → OmpReviewProjectService.save(state.copy(files=…))
User clicks "Submit Review"
  → save(state.copy(status=SUBMITTED))
```

### Injection path (IDE → next OMP run)

```
before_agent_start event
  → parseReview(): read .omp-review/review.md, check status == "submitted"
  → collect comments → format as markdown
  → delete .omp-review/review.md
  → return { message: { customType: "omp-review-context", content: [...] } }
       ↓ injected into agent context by OMP runtime
```

### Key patterns

- **OMP side**: event-driven hooks (`pi.on("before_agent_start", ...)`) with all state on disk; no internal state machine.
- **IntelliJ side**: IntelliJ Platform layering — `@Service` (data) → `ProjectActivity` (watcher) → `ToolWindowFactory` (UI). Layer-to-layer communication via platform **MessageBus pub/sub** (`Topic<Listener>`). No DI framework; the platform's service locator (`project.service<T>()`) is the injection mechanism.
- **VSCode side**: event-driven, same as OMP — no internal state machine. `ReviewService` holds state in-memory and broadcasts via a plain `vscode.EventEmitter` (the VSCode-native equivalent of IntelliJ's messageBus). VSCode-native instruments (Comments API, TreeDataProvider, TextDocumentContentProvider) replace the custom Swing UI.

---

## Key Directories

```
omp-review/
├── omp/
│   ├── package.json               # OMP extension manifest; omp.extensions = [./src/index.ts]
│   ├── tsconfig.json
│   └── src/
│       └── index.ts               # Entire OMP extension — markdown parsing, before_agent_start hook, human-review command
├── intellij/
│   ├── build.gradle.kts
│   ├── settings.gradle.kts
│   ├── gradle.properties
│   └── src/
│       ├── main/
│       │   ├── kotlin/io/github/ompreview/
│       │   │   ├── OmpReviewBundle.kt                      # i18n DynamicBundle
│       │   │   ├── model/ReviewModel.kt                    # plain data classes
│       │   │   ├── model/ReviewMarkdown.kt                 # review.md parse/render
│       │   │   ├── services/OmpReviewProjectService.kt     # review state, git diff, disk I/O, messageBus
│       │   │   ├── startup/OmpReviewStartupActivity.kt     # VFS watcher (review.md)
│       │   │   └── toolWindow/OmpReviewToolWindowFactory.kt # full Swing UI (503 lines)
│       │   └── resources/
│       │       ├── META-INF/plugin.xml                     # plugin descriptor
│       │       └── messages/OmpReviewBundle.properties
│       └── test/
│           ├── kotlin/io/github/ompreview/
│           │   └── OmpReviewPluginTest.kt                  # sole test (smoke)
│           └── testData/                                    # empty; reserved for fixtures
└── vscode/
    ├── package.json               # VSCode extension manifest; commands, views, configuration
    ├── esbuild.js                 # bundles src/extension.ts -> dist/extension.js (CJS)
    ├── tsconfig.json
    ├── resources/
    │   └── human-review-icon.svg  # activity-bar icon
    └── src/
        ├── extension.ts           # activate()/deactivate(); wires service, providers, commands
        ├── reviewService.ts       # review state, git diff, disk I/O, vscode.EventEmitter
        ├── contentProvider.ts     # virtual before/after diff documents (omp-review: scheme)
        ├── fileTree.ts            # Changed Files TreeDataProvider
        ├── comments.ts            # CommentController-backed comment threads
        ├── git.ts                 # git diff/show wrappers (no vscode import; unit-testable)
        ├── git.test.ts            # node:test coverage for git.ts
        └── model/
            ├── reviewModel.ts     # plain data types (no vscode import)
            ├── reviewMarkdown.ts  # review.md parse/render (no vscode import)
            └── reviewMarkdown.test.ts # node:test coverage for reviewMarkdown.ts
```

---

## Development Commands

### OMP extension (`omp/`)

```bash
cd omp
npm run typecheck        # tsc --noEmit — only available script
```

No build step. OMP loads `src/index.ts` directly at runtime.

### IntelliJ plugin (`intellij/`)

```bash
cd intellij
./gradlew runIde         # launch sandbox PyCharm with the plugin hot-loaded
./gradlew buildPlugin    # produce distributable .zip for manual install
./gradlew check          # run tests + all verification tasks
./gradlew test           # unit tests only
./gradlew verifyPlugin   # plugin API compatibility check (not tests)
```

Pre-configured IntelliJ run configurations live in `intellij/.run/`.

### VSCode extension (`vscode/`)

```bash
cd vscode
npm install
npm run typecheck        # tsc --noEmit
npm run test             # tsx --test — reviewMarkdown.test.ts + git.test.ts (no vscode import)
npm run compile          # esbuild bundle -> dist/extension.js
npm run watch            # esbuild --watch
npm run package          # vsce package -> .vsix
```

Press F5 in VS Code with `vscode/` open to launch the Extension Development Host.

### Installation (development)

Symlink the OMP extension into any project using OMP:
```bash
ln -s /path/to/omp-review/omp ~/.omp/extensions/omp-review
```

---

## Code Conventions & Common Patterns

### TypeScript (`omp/src/index.ts`)

- **Indent**: 2 spaces
- **Quotes**: double quotes everywhere
- **Semicolons**: always
- **`const`-first**: `let` only when reassignment is required
- **Nullish coalescing / optional chaining**: `??`, `?.` used throughout
- **No path aliases**: no `@/` prefix; no `baseUrl`; bare Node built-in imports only
- **Aligned assignments** in multi-line object literals for readability

```typescript
const comment: ParsedComment = {
  line:    startLine,
  lineEnd: endLine,
  body:    commentBody,
};
```

### Kotlin (`intellij/`)

- **Indent**: 4 spaces
- **No semicolons**
- **`val`-first**: `var` only for mutable UI state
- **Named arguments** in constructors and multi-line calls with trailing commas
- **`apply {}` blocks** for UI component initialization
- **Elvis early-return**: `?: return` on null guards
- **No star imports**

```kotlin
val comment = ReviewComment(
    id   = UUID.randomUUID().toString(),
    line = startLine,
    body = body,
)
```

### Cross-language house style

Both TypeScript and Kotlin use identical section dividers with en-dashes:
```
// ── Section Name ──────────────────────────────────────────────────────────────
```

### Markdown wire format

`.omp-review/review.md` is YAML-style frontmatter (`status: open|submitted`) followed by one `## <file>` heading per commented file and one `- id:<uuid> [line:<n>[-<m>]] <body>` bullet per comment. The two regexes (`FILE_HEADER`, `COMMENT_LINE`) are duplicated in `omp/src/index.ts` and `intellij/.../model/ReviewMarkdown.kt` — keep them identical.

### Naming

| Context | Convention |
|---|---|
| TypeScript interfaces/types | `PascalCase` (`ParsedReview`, `ReviewStatus`) |
| TypeScript functions/variables | `camelCase` (`parseReview`, `formatComments`) |
| TypeScript default export | camelCase function (`ompReview`) |
| Kotlin classes | `PascalCase`, `OmpReview` prefix for plugin types |
| Kotlin enums | `PascalCase` name, `UPPER_SNAKE_CASE` entries |
| Kotlin packages | `io.github.ompreview` (all lowercase) |

### Error handling

- **OMP**: `git` failures caught and treated as non-fatal (`catch { /* non-fatal */ }`). JSON parse failures return `null`. User-facing errors via `ctx.ui.notify("…", "warn")`.
- **Kotlin**: Markdown parse falls back to an empty `ReviewState` on any exception. Subprocess failures return `null`. No error dialogs or IDE logging — failures result in empty/null state silently.

### Async patterns

- **OMP**: `async/await` on hook callbacks; all file I/O is **synchronous** (`readFileSync`, `writeFileSync`).
- **IntelliJ**: `ProjectActivity.execute()` is a `suspend fun` (coroutine entry). UI work via `ApplicationManager.getApplication().invokeLater {}`. Background I/O (e.g. `git show`) via `executeOnPooledThread {}`. VFS callbacks arrive on EDT; disk reload dispatches to pooled thread.

### Comments

No JSDoc or KDoc. Code is self-documented by naming + section headers. Inline comments explain *why*:
```typescript
// Update the pointer last — the IntelliJ watcher fires on this write.
```
Boolean label comments:
```kotlin
addRootToWatch(ompReviewDir, /* watchRecursively= */ true)
```

---

## Important Files

| File | Purpose |
|---|---|
| `omp/src/index.ts` | OMP extension entry point; markdown parsing, `before_agent_start` hook, `human-review` command |
| `intellij/.../model/ReviewModel.kt` | Kotlin data model + review.md parse/render (`ReviewMarkdown.kt`) |
| `intellij/.../services/OmpReviewProjectService.kt` | Review state owner; git diff + disk I/O; messageBus publisher |
| `intellij/.../startup/OmpReviewStartupActivity.kt` | VFS watcher; triggers service reload on `.omp-review/review.md` write |
| `intellij/.../toolWindow/OmpReviewToolWindowFactory.kt` | Entire plugin UI: diff viewer, comment list, inlay rendering (503 lines) |
| `intellij/src/main/resources/META-INF/plugin.xml` | Plugin descriptor; registers tool window, startup activity, project service |
| `vscode/src/extension.ts` | VSCode extension entry point; activation, command/view wiring |
| `vscode/src/reviewService.ts` | Review state owner; git diff + disk I/O; `vscode.EventEmitter` publisher |
| `vscode/src/model/reviewMarkdown.ts` | review.md parse/render — TS port of `ReviewMarkdown.kt` |

---

## Runtime/Tooling Preferences

### OMP extension

- **Runtime**: OMP harness loads `src/index.ts` directly — no compile step, no bundler invocation.
- **Package manager**: No lockfile committed. `.idea/workspace.xml` notes `npm` as IDE default; treat `npm install` as canonical.
- **TypeScript**: `^5.4.0`, `strict: true`, `moduleResolution: bundler`, `target: ES2022`.
- **No linter or formatter configured** — maintain the existing style by observation.
- **Dependencies**: only Node.js built-ins (`fs`, `path`, `child_process`, `crypto`) + `@oh-my-pi/pi-coding-agent` peer dep.
- **No environment variables** — the extension is fully offline, reading/writing local files and invoking `git`.

### IntelliJ plugin

- **Build tool**: Gradle 9.5.0 (wrapper at `intellij/gradlew`). Configuration cache + build cache enabled.
- **Kotlin**: 2.3.0 JVM.
- **Platform target**: PyCharm 2026.1.2 (`intellijPlatform { pycharm("2026.1.2") }`).
- **No CI** — all tasks are run manually or via IntelliJ run configurations.

### VSCode extension

- **Runtime**: Node/npm; bundled via esbuild — `dist/extension.js` is the compiled entry point (`main` in `package.json`), unlike `omp/`'s direct-load model.
- **Package manager**: `npm install` (no lockfile committed, same posture as `omp/`).
- **TypeScript**: `^5.4.0`, `strict: true`, `module: commonjs`, `target: ES2022`.
- **Testing**: `tsx` + Node's built-in `node:test` runner (`npm run test`) — covers only the two modules with no `vscode` import (`reviewMarkdown.ts`, `git.ts`).
- **No linter or formatter configured** — matches `omp/`'s "no linter" posture.
- **Dependencies**: `@types/vscode`, `esbuild`, `@vscode/vsce`, `tsx` (dev-only) + Node.js built-ins at runtime.

### Review markdown format changes

When changing the `.omp-review/review.md` format, **manually update all three**:
1. `FILE_HEADER`/`COMMENT_LINE` regexes and parse/format functions in `omp/src/index.ts`
2. `ReviewMarkdown.parse`/`render` in `intellij/.../model/ReviewMarkdown.kt`
3. `parseReviewMarkdown`/`renderReviewMarkdown` in `vscode/src/model/reviewMarkdown.ts`

No code-generation pipeline exists.

---

## Testing & QA

### OMP extension

**No tests.** No test framework, no test files, no test script. Type safety via `npm run typecheck` only.

### IntelliJ plugin

- **Framework**: JUnit 4 (`junit:junit:4.13.2`) + IntelliJ Platform test harness (`TestFrameworkType.Platform` → `BasePlatformTestCase`)
- **Test location**: `intellij/src/test/kotlin/io/github/ompreview/`
- **File naming**: `*Test.kt`; method naming: `fun test*()` (JUnit 3-style, required by `BasePlatformTestCase`)
- **Coverage**: none configured

**Only one test exists:**
```kotlin
class OmpReviewPluginTest : BasePlatformTestCase() {
    fun testPluginLoads() {
        assertNotNull(project)
    }
}
```

**Run commands:**
```bash
cd intellij
./gradlew check          # tests + all verifications
./gradlew test           # tests only
./gradlew verifyPlugin   # plugin API compatibility (separate from tests)
```

**Manual/exploratory testing**: `./gradlew runIde` launches a sandbox PyCharm instance with the plugin loaded. The file `.omp-review/review.md` (status `submitted`) serves as a manual fixture.

### VSCode extension

- **Automated**: `vscode/src/model/reviewMarkdown.test.ts` and `vscode/src/git.test.ts` (`npm run test`, `tsx --test`) cover the markdown wire format and git integration — the two modules with no `vscode` import.
- **Manual only**: everything that touches the `vscode` API (tree view, comments, diff command, extension activation) has no automated test. Verify via the Extension Development Host (`F5` with `vscode/` open) — same "no CI" posture as the IntelliJ side.

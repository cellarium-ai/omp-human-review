import type { ExtensionAPI } from "@oh-my-pi/pi-coding-agent";
import { existsSync, readFileSync, unlinkSync } from "fs";
import { join } from "path";

// ── Types ──────────────────────────────────────────────────────────────────

type ReviewStatus = "open" | "submitted";

interface ParsedComment {
  line?: number;
  lineEnd?: number;
  category?: "Q" | "R";
  body: string;
}

interface ParsedFile {
  file: string;
  comments: ParsedComment[];
}

interface ParsedReview {
  status: ReviewStatus;
  files: ParsedFile[];
}

// ── File layout ───────────────────────────────────────────────────────────────
//
//   <project>/.omp-review/review.md   ← single file: status + pending comments
//
//   Format:
//     ---
//     status: open|submitted
//     ---
//
//     ## <file>
//
//   - id:<uuid> line:<n>[-<m>] cat:<Q|R> <body>
//   - id:<uuid> cat:<Q|R> <body>                ← file-level (no line: token)
//
//   Written by the IntelliJ plugin; read (and, on consumption, deleted) here.
//   Keep FILE_HEADER / COMMENT_LINE in sync with ReviewMarkdown.kt.

const FILE_HEADER = /^## (.+)$/;
const COMMENT_LINE = /^- id:[0-9a-fA-F-]+(?: line:(\d+)(?:-(\d+))?)?(?: cat:([QR]))? (.*)$/;

// Reverses the escaping ReviewMarkdown.escapeBody applies on the Kotlin side: "\\" -> "\",
// "\n" -> an actual newline. TS never writes review.md, so only unescaping is needed here.
function unescapeBody(raw: string): string {
  let result = "";
  for (let i = 0; i < raw.length; i++) {
    const c = raw[i];
    if (c === "\\" && i + 1 < raw.length) {
      const next = raw[i + 1];
      if (next === "n") { result += "\n"; i += 1; continue; }
      if (next === "\\") { result += "\\"; i += 1; continue; }
    }
    result += c;
  }
  return result;
}

function reviewPath(cwd: string): string {
  return join(cwd, ".omp-review", "review.md");
}

function parseReview(cwd: string): ParsedReview | null {
  const path = reviewPath(cwd);
  if (!existsSync(path)) return null;

  const lines = readFileSync(path, "utf8").split("\n");
  if (lines[0]?.trim() !== "---") return null;
  const statusLine = lines[1]?.trim();
  const status: ReviewStatus | null =
    statusLine === "status: submitted" ? "submitted" :
    statusLine === "status: open"      ? "open" :
    null;
  if (!status || lines[2]?.trim() !== "---") return null;

  const files: ParsedFile[] = [];
  let currentFile: string | null = null;
  let currentComments: ParsedComment[] = [];

  const flush = () => {
    if (currentFile && currentComments.length > 0) {
      files.push({ file: currentFile, comments: currentComments });
    }
  };

  for (const line of lines.slice(3)) {
    const header = line.match(FILE_HEADER);
    if (header) {
      flush();
      currentFile = header[1];
      currentComments = [];
      continue;
    }
    const match = line.match(COMMENT_LINE);
    if (!match) continue;
    currentComments.push({
      line:     match[1] ? parseInt(match[1], 10) : undefined,
      lineEnd:  match[2] ? parseInt(match[2], 10) : undefined,
      category: match[3] === "Q" ? "Q" : match[3] === "R" ? "R" : undefined,
      body:     unescapeBody(match[4]),
    });
  }
  flush();

  return { status, files };
}

function formatComments(files: ParsedFile[]): string[] {
  return files.flatMap((f) =>
    f.comments.map((c) => {
      const tag = c.category === "Q" ? "[Q]" : "[R]";
      const loc = c.line == null
        ? `${f.file} (file)`
        : `${f.file}:${c.lineEnd ? `${c.line}-${c.lineEnd}` : c.line}`;
      return `- ${tag} ${loc}: ${c.body}`;
    })
  );
}

// A review always delivers ALL pending comments in one shot (there is no
// partial-injection concept), so once read it is fully consumed — deleted,
// not rewritten.
function consumeReview(cwd: string) {
  try { unlinkSync(reviewPath(cwd)); } catch { /* non-fatal */ }
}

// ── Extension ─────────────────────────────────────────────────────────────────

export default function ompReview(pi: ExtensionAPI) {
  pi.setLabel("OMP Review");

  // Before each agent run: inject pending comments from a submitted review.
  pi.on("before_agent_start", async (_event, ctx) => {
    const parsed = parseReview(ctx.cwd);
    if (!parsed || parsed.status !== "submitted") return;

    const comments = formatComments(parsed.files);
    if (comments.length === 0) return;

    consumeReview(ctx.cwd);

    return {
      message: {
        customType: "omp-review-context",
        content: [
          {
            type: "text",
            text: [
              "## OMP Review — unresolved comments from the previous run",
              "",
              "[Q] = Question: answer it but make no code changes. [R] = Request: implement the required change.",
              "",
              ...comments,
            ].join("\n"),
          },
        ],
        display: true,
        details: { unresolvedCount: comments.length },
        attribution: "agent",
      },
    };
  });

  pi.registerCommand("human-review", {
    description: "send submitted review comments from .omp-review/review.md to OMP",
    handler: async (_args, ctx) => {
      const parsed = parseReview(ctx.cwd);

      if (!parsed) {
        ctx.ui.notify("No review found. Open OMP Review in your IDE, add comments, and submit.", "info");
        return;
      }

      if (parsed.status !== "submitted") {
        ctx.ui.notify("Review is not submitted yet — click Submit Review in the IDE first.", "info");
        return;
      }

      const comments = formatComments(parsed.files);
      if (comments.length === 0) {
        ctx.ui.notify("Submitted review has no comments.", "info");
        return;
      }

      // Delete before sending — before_agent_start fires for the message
      // below and must see nothing left to inject.
      consumeReview(ctx.cwd);

      const text = [
        "I have submitted a code review. Please address each of the following comments:",
        "",
        "[Q] = Question: answer it but make no code changes. [R] = Request: implement the required change.",
        "",
        ...comments,
        "",
        "Work through them one by one, following the [Q]/[R] instructions above.",
      ].join("\n");

      // No `deliverAs`: queuing as "followUp" only delivers once some other
      // turn starts and winds down, which is why comments used to sit until
      // the next prompt. Wait out any in-flight turn, then send directly so
      // this starts a turn right away.
      await ctx.waitForIdle();
      await pi.sendUserMessage([{ type: "text", text }]);
      ctx.ui.notify(`Injected ${comments.length} comment(s) into OMP.`, "info");
    },
  });
}

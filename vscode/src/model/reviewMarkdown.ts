import { ReviewComment, ReviewFile, ReviewState, ReviewStatus } from "./reviewModel";

const FILE_HEADER = /^## (.+)$/;
const COMMENT_LINE = /^- id:([0-9a-fA-F-]+)(?: line:(\d+)(?:-(\d+))?)?(?: cat:([QR]))? (.*)$/;

function escapeBody(body: string): string {
  const normalized = body.replace(/\r\n/g, "\n").replace(/\r/g, "\n");
  let out = "";
  for (const c of normalized) {
    if (c === "\\") out += "\\\\";
    else if (c === "\n") out += "\\n";
    else out += c;
  }
  return out;
}

function unescapeBody(raw: string): string {
  let out = "", i = 0;
  while (i < raw.length) {
    const c = raw[i];
    if (c === "\\" && i + 1 < raw.length) {
      const next = raw[i + 1];
      if (next === "n") { out += "\n"; i += 2; }
      else if (next === "\\") { out += "\\"; i += 2; }
      else { out += c; i += 1; }
    } else { out += c; i += 1; }
  }
  return out;
}

export function parseReviewMarkdown(text: string): ReviewState {
  const lines = text.split("\n");
  if (lines.length < 3 || lines[0].trim() !== "---") return { status: "open", files: [] };
  const statusLine = lines[1].trim();
  const status: ReviewStatus | null =
    statusLine === "status: submitted" ? "submitted" :
    statusLine === "status: open" ? "open" : null;
  if (!status || lines[2].trim() !== "---") return { status: "open", files: [] };

  const files: ReviewFile[] = [];
  let currentFile: string | null = null;
  let currentComments: ReviewComment[] = [];
  const flush = () => { if (currentFile && currentComments.length > 0) files.push({ file: currentFile, comments: currentComments }); };

  for (const line of lines.slice(3)) {
    const header = line.match(FILE_HEADER);
    if (header) { flush(); currentFile = header[1]; currentComments = []; continue; }
    const m = line.match(COMMENT_LINE);
    if (!m) continue;
    currentComments.push({
      id: m[1],
      ...(m[2] ? { line: parseInt(m[2], 10) } : {}),
      ...(m[3] ? { lineEnd: parseInt(m[3], 10) } : {}),
      category: m[4] === "Q" ? "Q" : "R",
      body: unescapeBody(m[5]),
    });
  }
  flush();
  return { status, files };
}

export function renderReviewMarkdown(state: ReviewState): string {
  let out = `---\nstatus: ${state.status === "submitted" ? "submitted" : "open"}\n---\n`;
  for (const file of state.files) {
    if (file.comments.length === 0) continue;
    out += `\n## ${file.file}\n\n`;
    for (const c of file.comments) {
      const lineToken = c.line == null ? "" : c.lineEnd != null ? ` line:${c.line}-${c.lineEnd}` : ` line:${c.line}`;
      const catToken = c.category === "Q" ? " cat:Q" : " cat:R";
      out += `- id:${c.id}${lineToken}${catToken} ${escapeBody(c.body)}\n`;
    }
  }
  return out;
}

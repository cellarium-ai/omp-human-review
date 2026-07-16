import { test } from "node:test";
import assert from "node:assert/strict";
import { parseReviewMarkdown, renderReviewMarkdown } from "./reviewMarkdown";
import type { ReviewState } from "./reviewModel";

test("render -> parse round-trips a multi-line body and a file-level comment", () => {
  const state: ReviewState = {
    status: "open",
    files: [
      {
        file: "src/foo.ts",
        comments: [
          { id: "11111111-1111-1111-1111-111111111111", line: 3, lineEnd: 5, category: "R" as const, body: "line one\nline two\\ trailing backslash" },
          { id: "22222222-2222-2222-2222-222222222222", category: "Q" as const, body: "file-level note" },
        ],
      },
    ],
  };
  assert.deepEqual(parseReviewMarkdown(renderReviewMarkdown(state)), state);
});

test("parses the review.md wire format used by the IntelliJ writer (.omp-review/review.md shape)", () => {
  const sample = [
    "---",
    "status: submitted",
    "---",
    "",
    "## omp/src/index.ts",
    "",
    "- id:bd97f54f-710d-4a41-8731-83ae9091ce49 line:28-34 Testing my comment, this is incredible",
    "- id:fe811779-453a-4c80-8add-6fd73f145d7b line:36-43 Another comment here",
    "- id:5ec13fe9-d45b-4134-9fc3-057749a4a941 line:95-109 This is a for loop",
    "- id:19567e96-bdb9-4830-aea7-fb9905616ee5 line:38-42 yeah",
    "",
  ].join("\n");
  const parsed = parseReviewMarkdown(sample);
  assert.equal(parsed.status, "submitted");
  assert.equal(parsed.files.length, 1);
  assert.equal(parsed.files[0].file, "omp/src/index.ts");
  assert.deepEqual(
    parsed.files[0].comments.map(c => [c.line, c.lineEnd]),
    [[28, 34], [36, 43], [95, 109], [38, 42]],
  );
  assert.ok(parsed.files[0].comments.every(c => c.category === "R"), "no cat: token defaults to R");
});

test("renders cat:Q and cat:R tokens correctly and round-trips them", () => {
  const state: ReviewState = {
    status: "open",
    files: [{
      file: "src/a.ts",
      comments: [
        { id: "aaaaaaaa-0000-0000-0000-000000000001", line: 1, category: "Q" as const, body: "why?" },
        { id: "aaaaaaaa-0000-0000-0000-000000000002", category: "R" as const, body: "fix this" },
      ],
    }],
  };
  const rendered = renderReviewMarkdown(state);
  assert.ok(rendered.includes(" cat:Q "), "Q token present in output");
  assert.ok(rendered.includes(" cat:R "), "R token present in output");
  assert.deepEqual(parseReviewMarkdown(rendered), state);
});

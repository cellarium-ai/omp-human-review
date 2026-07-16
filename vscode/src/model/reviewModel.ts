export interface ReviewComment {
  id: string;
  line?: number;
  lineEnd?: number;
  category?: "Q" | "R";
  body: string;
}
export interface ReviewFile {
  file: string;
  comments: ReviewComment[];
}
export type ReviewStatus = "open" | "submitted";
export interface ReviewState {
  status: ReviewStatus;
  files: ReviewFile[];
}

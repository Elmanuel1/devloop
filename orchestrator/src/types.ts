import type { Context } from "hono";

// ─── Base Event ───────────────────────────────────────────────────────────────

export interface OrchestratorEvent {
  id: string;
  source: string;
  type: string;
  raw: unknown;
}

// ─── Abstract by domain (tool-agnostic names, NOT tool-specific) ──────────────

export interface MessagingEvent extends OrchestratorEvent {
  message: string;
  senderId: string;
  senderName: string;
  ack: (text: string) => Promise<void>; // immediate reply only
}

export interface SourceControlEvent extends OrchestratorEvent {
  prNumber: number;
  branch: string;
}

export interface DocumentEvent extends OrchestratorEvent {
  pageId: string;
  designId: string;
}

// ─── Concrete Events ──────────────────────────────────────────────────────────

export interface TaskRequestEvent extends MessagingEvent {
  type: "task:requested";
}

export interface CiFailedEvent extends SourceControlEvent {
  type: "ci:failed";
  logs: string;
}

export interface CiPassedEvent extends SourceControlEvent {
  type: "ci:passed";
}

export interface PrApprovedEvent extends SourceControlEvent {
  type: "pr:approved";
}

export interface ChangesRequestedEvent extends SourceControlEvent {
  type: "pr:changes_requested";
  comments: string[];
}

export interface PrMergedEvent extends SourceControlEvent {
  type: "pr:merged";
}

export interface PrCommentEvent extends SourceControlEvent {
  type: "pr:comment";
  comments: string[];
}

export interface PageApprovedEvent extends DocumentEvent {
  type: "page:approved";
}

export interface NewCommentEvent extends DocumentEvent {
  type: "page:comment";
  comments: string[];
}

// ─── Internal orchestrator events ─────────────────────────────────────────────

export interface AgentCompletedEvent extends OrchestratorEvent {
  type: "agent:completed";
  agentName: string;
  designId: string;
  outputKey: string;
  outputPath: string; // file path, not content
}

export interface StageCompletedEvent extends OrchestratorEvent {
  type: "stage:completed";
  designId: string;
  stage: string;
}

// ─── Union Type ───────────────────────────────────────────────────────────────

export type OrchestratorEventUnion =
  | TaskRequestEvent
  | CiFailedEvent
  | CiPassedEvent
  | PrApprovedEvent
  | ChangesRequestedEvent
  | PrMergedEvent
  | PrCommentEvent
  | PageApprovedEvent
  | NewCommentEvent
  | AgentCompletedEvent
  | StageCompletedEvent;

// ─── Task Handler ─────────────────────────────────────────────────────────────

export interface TaskHandler {
  queue: string;
  matches(event: OrchestratorEvent): boolean;
  handle(event: OrchestratorEvent): Promise<void>;
}

// ─── Route Key ────────────────────────────────────────────────────────────────

export type RouteKey =
  | "post:architect"
  | "post:code_writer"
  | "post:reviewer"
  | "post:approval"
  | "post:ci_passed"
  | "post:merge";

// ─── Webhook Verifier ─────────────────────────────────────────────────────────

export interface WebhookVerifier {
  verify(c: Context): Promise<void>;
}

// ─── Event Parser ─────────────────────────────────────────────────────────────

export interface EventParser<T extends OrchestratorEvent = OrchestratorEvent> {
  parse(input: any): Promise<T[]>; // eslint-disable-line @typescript-eslint/no-explicit-any
}

// ─── Notification Channel ─────────────────────────────────────────────────────

export interface NotificationChannel {
  send(message: string): Promise<void>;
}

// ─── Agent ────────────────────────────────────────────────────────────────────

export interface AgentOptions {
  agent: string;
  prompt: string;
  worktreeBranch?: string;
  timeoutMs?: number;
  heartbeatMs?: number;
  cwd?: string;
}

export interface ParsedAgentOutput {
  result?: string;
  cost_usd?: number;
  duration_ms?: number;
  duration_api_ms?: number;
  num_turns?: number;
  is_error?: boolean;
  session_id?: string;
}

export interface AgentResult {
  success: boolean;
  output: string;
  parsed?: ParsedAgentOutput;
  durationMs: number;
}

// ─── Queue ────────────────────────────────────────────────────────────────────

export interface TaskQueue {
  push(event: OrchestratorEvent): void;
  on(event: "drain", cb: () => void): void;
  destroy(): void;
}

export type QueueWorker = (event: OrchestratorEvent) => Promise<void>;

export interface TaskQueueFactory {
  create(name: string, worker: QueueWorker, concurrency?: number): TaskQueue;
}

// ─── Store Records ────────────────────────────────────────────────────────────

export type DesignStatus =
  | "requested"
  | "designing"
  | "in_review"
  | "approved"
  | "coding"
  | "complete"
  | "failed";

export interface DesignRecord {
  id: string;
  slack_channel: string;
  slack_thread_ts: string;
  requested_by: string;
  request_text: string;
  confluence_page_id: string | null;
  jira_epic_key: string | null;
  status: DesignStatus;
  created_at: string;
  updated_at: string;
}

export type PRStatus =
  | "open"
  | "ci_failing"
  | "ci_passed"
  | "in_review"
  | "changes_requested"
  | "approved"
  | "merged"
  | "failed";

export interface DesignOutputRecord {
  id: string;
  design_id: string;
  stage: string;
  agent: string;
  output: string;
  cost_usd: number | null;
  duration_ms: number | null;
  created_at: string;
}

export interface PRStateRecord {
  id: string;
  design_id: string;
  pr_number: number;
  branch: string;
  jira_subtask_key: string | null;
  status: PRStatus;
  ci_attempts: number;
  review_attempts: number;
  created_at: string;
  updated_at: string;
}

// ─── Integration Interfaces ───────────────────────────────────────────────────

export interface JiraIssueFields {
  summary: string;
  description?: string;
  issuetype?: { name: string };
  [key: string]: unknown;
}

export interface JiraClient {
  createIssue(fields: JiraIssueFields): Promise<{ key: string; id: string }>;
  createSubTask(parentKey: string, fields: JiraIssueFields): Promise<{ key: string; id: string }>;
  getSubTasks(parentKey: string): Promise<Array<{ key: string; summary: string }>>;
  transition(issueKey: string, transitionName: string): Promise<void>;
  addComment(issueKey: string, body: string): Promise<void>;
}

export interface ConfluencePage {
  id: string;
  title: string;
  version: number;
  body?: string;
}

export interface ConfluenceComment {
  id: string;
  body: string;
  author: string;
  createdAt: string;
}

export interface ConfluenceClient {
  createPage(title: string, body: string, parentId?: string): Promise<ConfluencePage>;
  updatePage(pageId: string, title: string, body: string, version: number): Promise<ConfluencePage>;
  findPage(title: string): Promise<ConfluencePage | null>;
  getContentState(pageId: string): Promise<string | null>;
  setContentState(pageId: string, key: string, value: string): Promise<void>;
  getPagesInReview(): Promise<ConfluencePage[]>;
  getNewComments(pageId: string, since: string): Promise<ConfluenceComment[]>;
}

export interface GitHubPR {
  number: number;
  title: string;
  state: string;
  merged: boolean;
  head: { ref: string };
  base: { ref: string };
}

export interface GitHubReviewComment {
  id: number;
  body: string;
  path?: string;
  line?: number;
  user: { login: string };
}

export interface GitHubClient {
  getPR(prNumber: number): Promise<GitHubPR | null>;
  findPR(branch: string): Promise<GitHubPR | null>;
  mergePR(prNumber: number, method?: "merge" | "squash" | "rebase"): Promise<void>;
  getPRReviewComments(prNumber: number): Promise<GitHubReviewComment[]>;
  getCheckRunLogs(runId: string): Promise<string>;
  getPRBranch(prNumber: number): Promise<string>;
}

export interface SlackClient {
  send(message: string, threadTs?: string): Promise<void>;
  postMessage(channel: string, text: string, threadTs?: string): Promise<void>;
  getUserName(userId: string): Promise<string>;
}

// ─── Base Event ─────────────────────────────────────────────────────────────

export interface BaseEvent {
  type: string;
  designId?: string;
  timestamp: number;
}

// ─── Concrete Events ─────────────────────────────────────────────────────────

export interface TaskRequestEvent extends BaseEvent {
  type: "task:requested";
  slackChannelId: string;
  slackThreadTs: string;
  requestText: string;
  requestedBy: string;
}

export interface PageApprovedEvent extends BaseEvent {
  type: "page:approved";
  designId: string;
  pageId: string;
}

export interface NewCommentEvent extends BaseEvent {
  type: "page:comment";
  designId: string;
  pageId: string;
  commentId: string;
  commentBody: string;
  author: string;
}

export interface StageCompletedEvent extends BaseEvent {
  type: "stage:completed";
  designId: string;
  stage: "architect" | "code_writer" | "reviewer";
  outputId: string;
}

export interface CIFailedEvent extends BaseEvent {
  type: "ci:failed";
  designId: string;
  prNumber: number;
  runId: string;
  branch: string;
}

export interface CIPassedEvent extends BaseEvent {
  type: "ci:passed";
  designId: string;
  prNumber: number;
  branch: string;
}

export interface PRChangesRequestedEvent extends BaseEvent {
  type: "pr:changes_requested";
  designId: string;
  prNumber: number;
  reviewBody: string;
  reviewer: string;
}

export interface PRCommentEvent extends BaseEvent {
  type: "pr:comment";
  designId: string;
  prNumber: number;
  commentBody: string;
  commentPath?: string;
  commentLine?: number;
  author: string;
}

export interface PRApprovedEvent extends BaseEvent {
  type: "pr:approved";
  designId: string;
  prNumber: number;
}

export interface ReviewCodeEvent extends BaseEvent {
  type: "review:code";
  designId: string;
  prNumber: number;
  branch: string;
}

export interface RetryEvent extends BaseEvent {
  type: "retry";
  originalEvent: OrchestratorEvent;
  attempt: number;
}

// ─── Union Type ───────────────────────────────────────────────────────────────

export type OrchestratorEvent =
  | TaskRequestEvent
  | PageApprovedEvent
  | NewCommentEvent
  | StageCompletedEvent
  | CIFailedEvent
  | CIPassedEvent
  | PRChangesRequestedEvent
  | PRCommentEvent
  | PRApprovedEvent
  | ReviewCodeEvent
  | RetryEvent;

// ─── Task Handler ─────────────────────────────────────────────────────────────

export interface TaskHandler<E extends OrchestratorEvent> {
  matches(event: OrchestratorEvent): event is E;
  handle(event: E): Promise<void>;
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
  verify(headers: Record<string, string>, body: string): Promise<boolean>;
}

// ─── Event Parser ─────────────────────────────────────────────────────────────

export interface EventParser {
  parse(headers: Record<string, string>, body: string): OrchestratorEvent | null;
}

// ─── Notification Channel ─────────────────────────────────────────────────────

export interface NotificationChannel {
  send(message: string, threadId?: string): Promise<void>;
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

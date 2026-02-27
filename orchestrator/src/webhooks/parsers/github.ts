import type { Context } from "hono";
import type {
  EventParser,
  SourceControlEvent,
  CiFailedEvent,
  CiPassedEvent,
  PrApprovedEvent,
  ChangesRequestedEvent,
  PrMergedEvent,
  PrCommentEvent,
} from "../../types.ts";

export class GitHubEventParser implements EventParser<SourceControlEvent> {
  async parse(c: Context): Promise<SourceControlEvent[]> {
    const eventType = c.req.header("X-GitHub-Event");
    const body = await c.req.json() as Record<string, unknown>;

    if (!eventType) {
      return [];
    }

    if (eventType === "check_suite") {
      return this.parseCheckSuite(body);
    }

    if (eventType === "pull_request_review") {
      return this.parsePullRequestReview(body);
    }

    if (eventType === "pull_request") {
      return this.parsePullRequest(body);
    }

    if (eventType === "issue_comment") {
      return this.parseIssueComment(body);
    }

    return [];
  }

  private parseCheckSuite(body: Record<string, unknown>): SourceControlEvent[] {
    const suite = body["check_suite"] as Record<string, unknown> | undefined;
    if (!suite) return [];

    const conclusion = suite["conclusion"] as string | undefined;
    const headBranch = (suite["head_branch"] as string | undefined) ?? "";
    const pullRequests = (suite["pull_requests"] as Array<Record<string, unknown>> | undefined) ?? [];
    const prNumber = pullRequests.length > 0
      ? ((pullRequests[0]["number"] as number | undefined) ?? 0)
      : 0;

    if (conclusion === "failure" || conclusion === "timed_out") {
      const logs = extractCheckRunLogs(body);
      const event: CiFailedEvent = {
        id: crypto.randomUUID(),
        source: "github",
        type: "ci:failed",
        raw: body,
        prNumber,
        branch: headBranch,
        logs,
      };
      return [event];
    }

    if (conclusion === "success") {
      const event: CiPassedEvent = {
        id: crypto.randomUUID(),
        source: "github",
        type: "ci:passed",
        raw: body,
        prNumber,
        branch: headBranch,
      };
      return [event];
    }

    return [];
  }

  private parsePullRequestReview(body: Record<string, unknown>): SourceControlEvent[] {
    const review = body["review"] as Record<string, unknown> | undefined;
    const pr = body["pull_request"] as Record<string, unknown> | undefined;

    if (!review || !pr) return [];

    const state = review["state"] as string | undefined;
    const prNumber = (pr["number"] as number | undefined) ?? 0;
    const branch = ((pr["head"] as Record<string, unknown> | undefined)?.["ref"] as string | undefined) ?? "";

    if (state === "approved") {
      const event: PrApprovedEvent = {
        id: crypto.randomUUID(),
        source: "github",
        type: "pr:approved",
        raw: body,
        prNumber,
        branch,
      };
      return [event];
    }

    if (state === "changes_requested") {
      const reviewBody = (review["body"] as string | undefined) ?? "";
      const event: ChangesRequestedEvent = {
        id: crypto.randomUUID(),
        source: "github",
        type: "pr:changes_requested",
        raw: body,
        prNumber,
        branch,
        comments: reviewBody ? [reviewBody] : [],
      };
      return [event];
    }

    return [];
  }

  private parsePullRequest(body: Record<string, unknown>): SourceControlEvent[] {
    const action = body["action"] as string | undefined;
    const pr = body["pull_request"] as Record<string, unknown> | undefined;

    if (!pr) return [];

    const merged = pr["merged"] as boolean | undefined;
    const prNumber = (pr["number"] as number | undefined) ?? 0;
    const branch = ((pr["head"] as Record<string, unknown> | undefined)?.["ref"] as string | undefined) ?? "";

    if (action === "closed" && merged === true) {
      const event: PrMergedEvent = {
        id: crypto.randomUUID(),
        source: "github",
        type: "pr:merged",
        raw: body,
        prNumber,
        branch,
      };
      return [event];
    }

    return [];
  }

  private parseIssueComment(body: Record<string, unknown>): SourceControlEvent[] {
    const issue = body["issue"] as Record<string, unknown> | undefined;
    const comment = body["comment"] as Record<string, unknown> | undefined;

    if (!issue || !comment) return [];

    // Only handle comments on pull requests (issues have pull_request field)
    const pullRequest = issue["pull_request"] as Record<string, unknown> | undefined;
    if (!pullRequest) return [];

    const prNumber = (issue["number"] as number | undefined) ?? 0;

    // For issue_comment events, we don't have direct branch info from the issue object.
    // We extract it from the issue body or leave as empty and rely on lookups downstream.
    const commentBody = (comment["body"] as string | undefined) ?? "";

    const event: PrCommentEvent = {
      id: crypto.randomUUID(),
      source: "github",
      type: "pr:comment",
      raw: body,
      prNumber,
      branch: "",
      comments: commentBody ? [commentBody] : [],
    };
    return [event];
  }

  extractIssueKey(branch: string): string | null {
    const match = branch.match(/(?:feature|fix|chore)\/([A-Z]+-\d+)/i);
    if (!match) return null;
    return match[1].toUpperCase();
  }
}

function extractCheckRunLogs(body: Record<string, unknown>): string {
  const checkRuns = body["check_runs"] as Array<Record<string, unknown>> | undefined;
  if (!checkRuns || checkRuns.length === 0) return "";

  const logs = checkRuns
    .map((run) => {
      const output = run["output"] as Record<string, unknown> | undefined;
      if (!output) return "";
      const title = (output["title"] as string | undefined) ?? "";
      const summary = (output["summary"] as string | undefined) ?? "";
      return `${title}\n${summary}`.trim();
    })
    .filter((s) => s.length > 0)
    .join("\n\n");

  return logs;
}

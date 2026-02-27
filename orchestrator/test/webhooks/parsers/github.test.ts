import { describe, test, expect } from "bun:test";
import { GitHubEventParser } from "../../../src/webhooks/parsers/github.ts";
import type {
  CiFailedEvent,
  CiPassedEvent,
  PrApprovedEvent,
  ChangesRequestedEvent,
  PrMergedEvent,
  PrCommentEvent,
} from "../../../src/types.ts";

function makeContext(eventType: string | null, body: Record<string, unknown>): import("hono").Context {
  const headers: Record<string, string> = {};
  if (eventType !== null) headers["X-GitHub-Event"] = eventType;

  return {
    req: {
      header(name: string): string | undefined {
        return headers[name];
      },
      async json(): Promise<Record<string, unknown>> {
        return body;
      },
    },
  } as unknown as import("hono").Context;
}

const parser = new GitHubEventParser();

describe("GitHubEventParser — check_suite events", () => {
  test("check_suite failure → CiFailedEvent", async () => {
    const body = {
      check_suite: {
        conclusion: "failure",
        head_branch: "feature/TOS-40-payments",
        pull_requests: [{ number: 42 }],
      },
    };
    const c = makeContext("check_suite", body);
    const events = await parser.parse(c);

    expect(events).toHaveLength(1);
    const event = events[0] as CiFailedEvent;
    expect(event.type).toBe("ci:failed");
    expect(event.source).toBe("github");
    expect(event.prNumber).toBe(42);
    expect(event.branch).toBe("feature/TOS-40-payments");
    expect(typeof event.id).toBe("string");
    expect(event.id.length).toBeGreaterThan(0);
  });

  test("check_suite timed_out → CiFailedEvent", async () => {
    const body = {
      check_suite: {
        conclusion: "timed_out",
        head_branch: "feature/TOS-41-auth",
        pull_requests: [{ number: 55 }],
      },
    };
    const events = await parser.parse(makeContext("check_suite", body));
    expect(events).toHaveLength(1);
    expect(events[0].type).toBe("ci:failed");
  });

  test("check_suite success → CiPassedEvent", async () => {
    const body = {
      check_suite: {
        conclusion: "success",
        head_branch: "feature/TOS-40-payments",
        pull_requests: [{ number: 42 }],
      },
    };
    const events = await parser.parse(makeContext("check_suite", body));

    expect(events).toHaveLength(1);
    const event = events[0] as CiPassedEvent;
    expect(event.type).toBe("ci:passed");
    expect(event.prNumber).toBe(42);
    expect(event.branch).toBe("feature/TOS-40-payments");
  });

  test("check_suite with no pull_requests → prNumber defaults to 0", async () => {
    const body = {
      check_suite: {
        conclusion: "success",
        head_branch: "main",
        pull_requests: [],
      },
    };
    const events = await parser.parse(makeContext("check_suite", body));
    expect(events).toHaveLength(1);
    expect(events[0].prNumber).toBe(0);
  });

  test("check_suite with pending conclusion → empty", async () => {
    const body = {
      check_suite: {
        conclusion: null,
        head_branch: "feature/TOS-40-payments",
        pull_requests: [{ number: 42 }],
      },
    };
    const events = await parser.parse(makeContext("check_suite", body));
    expect(events).toHaveLength(0);
  });
});

describe("GitHubEventParser — pull_request_review events", () => {
  test("approved → PrApprovedEvent", async () => {
    const body = {
      review: { state: "approved", body: "Looks good!" },
      pull_request: { number: 10, head: { ref: "feature/TOS-42-auth" } },
    };
    const events = await parser.parse(makeContext("pull_request_review", body));

    expect(events).toHaveLength(1);
    const event = events[0] as PrApprovedEvent;
    expect(event.type).toBe("pr:approved");
    expect(event.prNumber).toBe(10);
    expect(event.branch).toBe("feature/TOS-42-auth");
  });

  test("changes_requested → ChangesRequestedEvent with comments", async () => {
    const body = {
      review: { state: "changes_requested", body: "Please fix the error handling" },
      pull_request: { number: 10, head: { ref: "feature/TOS-42-auth" } },
    };
    const events = await parser.parse(makeContext("pull_request_review", body));

    expect(events).toHaveLength(1);
    const event = events[0] as ChangesRequestedEvent;
    expect(event.type).toBe("pr:changes_requested");
    expect(event.comments).toEqual(["Please fix the error handling"]);
  });

  test("changes_requested with empty body → comments is empty array", async () => {
    const body = {
      review: { state: "changes_requested", body: "" },
      pull_request: { number: 10, head: { ref: "feature/TOS-42-auth" } },
    };
    const events = await parser.parse(makeContext("pull_request_review", body));
    const event = events[0] as ChangesRequestedEvent;
    expect(event.comments).toEqual([]);
  });

  test("commented state → empty events", async () => {
    const body = {
      review: { state: "commented", body: "LGTM" },
      pull_request: { number: 10, head: { ref: "feature/TOS-42-auth" } },
    };
    const events = await parser.parse(makeContext("pull_request_review", body));
    expect(events).toHaveLength(0);
  });
});

describe("GitHubEventParser — pull_request events", () => {
  test("closed + merged → PrMergedEvent", async () => {
    const body = {
      action: "closed",
      pull_request: {
        number: 33,
        merged: true,
        head: { ref: "feature/TOS-43-dashboard" },
      },
    };
    const events = await parser.parse(makeContext("pull_request", body));

    expect(events).toHaveLength(1);
    const event = events[0] as PrMergedEvent;
    expect(event.type).toBe("pr:merged");
    expect(event.prNumber).toBe(33);
    expect(event.branch).toBe("feature/TOS-43-dashboard");
  });

  test("closed but not merged → empty", async () => {
    const body = {
      action: "closed",
      pull_request: {
        number: 33,
        merged: false,
        head: { ref: "feature/TOS-43-dashboard" },
      },
    };
    const events = await parser.parse(makeContext("pull_request", body));
    expect(events).toHaveLength(0);
  });

  test("opened action → empty", async () => {
    const body = {
      action: "opened",
      pull_request: {
        number: 33,
        merged: false,
        head: { ref: "feature/TOS-43-dashboard" },
      },
    };
    const events = await parser.parse(makeContext("pull_request", body));
    expect(events).toHaveLength(0);
  });
});

describe("GitHubEventParser — issue_comment events", () => {
  test("comment on PR → PrCommentEvent", async () => {
    const body = {
      issue: {
        number: 99,
        pull_request: { url: "https://api.github.com/repos/org/repo/pulls/99" },
      },
      comment: {
        id: 1234,
        body: "Could you also add tests?",
        user: { login: "reviewer" },
      },
    };
    const events = await parser.parse(makeContext("issue_comment", body));

    expect(events).toHaveLength(1);
    const event = events[0] as PrCommentEvent;
    expect(event.type).toBe("pr:comment");
    expect(event.prNumber).toBe(99);
    expect(event.comments).toEqual(["Could you also add tests?"]);
  });

  test("comment on regular issue (no pull_request field) → empty", async () => {
    const body = {
      issue: {
        number: 10,
        // no pull_request field
      },
      comment: {
        id: 5678,
        body: "This is a bug",
        user: { login: "user" },
      },
    };
    const events = await parser.parse(makeContext("issue_comment", body));
    expect(events).toHaveLength(0);
  });
});

describe("GitHubEventParser — unknown events", () => {
  test("unknown event type → empty array", async () => {
    const events = await parser.parse(makeContext("push", { ref: "refs/heads/main" }));
    expect(events).toHaveLength(0);
  });

  test("missing X-GitHub-Event header → empty array", async () => {
    const events = await parser.parse(makeContext(null, {}));
    expect(events).toHaveLength(0);
  });
});

describe("GitHubEventParser — extractIssueKey", () => {
  test("extracts issue key from feature branch", () => {
    expect(parser.extractIssueKey("feature/TOS-40-payments")).toBe("TOS-40");
  });

  test("extracts issue key from fix branch", () => {
    expect(parser.extractIssueKey("fix/TOS-99-critical-bug")).toBe("TOS-99");
  });

  test("extracts issue key from chore branch", () => {
    expect(parser.extractIssueKey("chore/TOS-1-setup")).toBe("TOS-1");
  });

  test("returns null for branch without issue key", () => {
    expect(parser.extractIssueKey("main")).toBeNull();
    expect(parser.extractIssueKey("feature/no-key-here")).toBeNull();
  });

  test("normalizes issue key to uppercase", () => {
    expect(parser.extractIssueKey("feature/tos-40-payments")).toBe("TOS-40");
  });
});

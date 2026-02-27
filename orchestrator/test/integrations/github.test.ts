import { describe, test, expect, beforeEach, mock, afterEach } from "bun:test";
import { GitHubOctokitClient } from "../../src/integrations/github.ts";

// We mock @octokit/rest at the module level using Bun's module mocking support
// by patching the Octokit constructor's methods directly on the instance.

const OWNER = "testorg";
const REPO = "testrepo";
const TOKEN = "ghp_test_token";

function makeOctokitMock(overrides: Record<string, unknown> = {}) {
  return {
    pulls: {
      get: mock(async () => ({ data: {} })),
      list: mock(async () => ({ data: [] })),
      merge: mock(async () => ({ data: {} })),
      listReviewComments: mock(async () => ({ data: [] })),
      ...((overrides.pulls ?? {}) as object),
    },
    actions: {
      listJobsForWorkflowRun: mock(async () => ({ data: { jobs: [] } })),
      ...((overrides.actions ?? {}) as object),
    },
  };
}

// We test by subclassing to inject a mock Octokit instance
function makeClientWithOctokit(octokitMock: ReturnType<typeof makeOctokitMock>) {
  const client = new GitHubOctokitClient({ token: TOKEN, owner: OWNER, repo: REPO });
  // Replace the internal octokit instance with our mock
  (client as unknown as Record<string, unknown>).octokit = octokitMock;
  return client;
}

describe("GitHubOctokitClient — getPR", () => {
  test("returns mapped PR data from octokit.pulls.get", async () => {
    const octokitMock = makeOctokitMock({
      pulls: {
        get: mock(async () => ({
          data: {
            number: 42,
            title: "Add feature",
            state: "open",
            merged: false,
            head: { ref: "feature/TOS-42-payments" },
            base: { ref: "main" },
          },
        })),
      },
    });

    const client = makeClientWithOctokit(octokitMock);
    const result = await client.getPR(42);

    expect(result).not.toBeNull();
    expect(result!.number).toBe(42);
    expect(result!.title).toBe("Add feature");
    expect(result!.state).toBe("open");
    expect(result!.merged).toBe(false);
    expect(result!.head.ref).toBe("feature/TOS-42-payments");
    expect(result!.base.ref).toBe("main");
  });

  test("passes correct owner, repo, and pull_number to octokit", async () => {
    const getMock = mock(async () => ({
      data: {
        number: 10,
        title: "test",
        state: "open",
        merged: false,
        head: { ref: "branch" },
        base: { ref: "main" },
      },
    }));

    const octokitMock = makeOctokitMock({ pulls: { get: getMock } });
    const client = makeClientWithOctokit(octokitMock);
    await client.getPR(10);

    expect(getMock).toHaveBeenCalledWith({
      owner: OWNER,
      repo: REPO,
      pull_number: 10,
    });
  });

  test("returns null on 404", async () => {
    const octokitMock = makeOctokitMock({
      pulls: {
        get: mock(async () => {
          const err = new Error("Not Found") as Error & { status: number };
          err.status = 404;
          throw err;
        }),
      },
    });

    const client = makeClientWithOctokit(octokitMock);
    const result = await client.getPR(9999);
    expect(result).toBeNull();
  });

  test("rethrows non-404 errors", async () => {
    const octokitMock = makeOctokitMock({
      pulls: {
        get: mock(async () => {
          const err = new Error("Server Error") as Error & { status: number };
          err.status = 500;
          throw err;
        }),
      },
    });

    const client = makeClientWithOctokit(octokitMock);
    await expect(client.getPR(42)).rejects.toThrow("Server Error");
  });
});

describe("GitHubOctokitClient — findPR", () => {
  test("returns null when no open PRs match the branch", async () => {
    const octokitMock = makeOctokitMock({
      pulls: {
        list: mock(async () => ({ data: [] })),
      },
    });

    const client = makeClientWithOctokit(octokitMock);
    const result = await client.findPR("feature/TOS-42-payments");
    expect(result).toBeNull();
  });

  test("returns first matching PR", async () => {
    const octokitMock = makeOctokitMock({
      pulls: {
        list: mock(async () => ({
          data: [
            {
              number: 7,
              title: "My PR",
              state: "open",
              head: { ref: "feature/my-branch" },
              base: { ref: "main" },
            },
          ],
        })),
      },
    });

    const client = makeClientWithOctokit(octokitMock);
    const result = await client.findPR("feature/my-branch");

    expect(result).not.toBeNull();
    expect(result!.number).toBe(7);
    expect(result!.merged).toBe(false);
  });

  test("includes owner prefix in head param", async () => {
    const listMock = mock(async () => ({ data: [] }));
    const octokitMock = makeOctokitMock({ pulls: { list: listMock } });
    const client = makeClientWithOctokit(octokitMock);
    await client.findPR("my-branch");

    expect(listMock).toHaveBeenCalledWith(
      expect.objectContaining({ head: `${OWNER}:my-branch` })
    );
  });
});

describe("GitHubOctokitClient — mergePR", () => {
  test("calls octokit.pulls.merge with squash by default", async () => {
    const mergeMock = mock(async () => ({ data: { merged: true, message: "Merged" } }));
    const octokitMock = makeOctokitMock({ pulls: { merge: mergeMock } });
    const client = makeClientWithOctokit(octokitMock);

    await client.mergePR(42);

    expect(mergeMock).toHaveBeenCalledWith({
      owner: OWNER,
      repo: REPO,
      pull_number: 42,
      merge_method: "squash",
    });
  });

  test("passes custom merge method", async () => {
    const mergeMock = mock(async () => ({ data: { merged: true } }));
    const octokitMock = makeOctokitMock({ pulls: { merge: mergeMock } });
    const client = makeClientWithOctokit(octokitMock);

    await client.mergePR(42, "rebase");

    expect(mergeMock).toHaveBeenCalledWith(
      expect.objectContaining({ merge_method: "rebase" })
    );
  });
});

describe("GitHubOctokitClient — getPRReviewComments", () => {
  test("returns mapped review comments", async () => {
    const octokitMock = makeOctokitMock({
      pulls: {
        listReviewComments: mock(async () => ({
          data: [
            { id: 1, body: "Change this", path: "src/foo.ts", line: 10, user: { login: "reviewer" } },
            { id: 2, body: "LGTM", path: "src/bar.ts", line: undefined, user: { login: "other" } },
          ],
        })),
      },
    });

    const client = makeClientWithOctokit(octokitMock);
    const result = await client.getPRReviewComments(42);

    expect(result).toHaveLength(2);
    expect(result[0].id).toBe(1);
    expect(result[0].body).toBe("Change this");
    expect(result[0].path).toBe("src/foo.ts");
    expect(result[0].line).toBe(10);
    expect(result[0].user.login).toBe("reviewer");
  });

  test("returns empty array when no review comments", async () => {
    const octokitMock = makeOctokitMock({
      pulls: {
        listReviewComments: mock(async () => ({ data: [] })),
      },
    });

    const client = makeClientWithOctokit(octokitMock);
    const result = await client.getPRReviewComments(42);
    expect(result).toHaveLength(0);
  });
});

describe("GitHubOctokitClient — getCheckRunLogs", () => {
  test("returns formatted job and step summary from workflow run", async () => {
    const octokitMock = makeOctokitMock({
      actions: {
        listJobsForWorkflowRun: mock(async () => ({
          data: {
            jobs: [
              {
                name: "build",
                conclusion: "failure",
                status: "completed",
                steps: [
                  { name: "checkout", conclusion: "success", status: "completed" },
                  { name: "typecheck", conclusion: "failure", status: "completed" },
                ],
              },
            ],
          },
        })),
      },
    });

    const client = makeClientWithOctokit(octokitMock);
    const result = await client.getCheckRunLogs("12345");

    expect(result).toContain("build");
    expect(result).toContain("failure");
    expect(result).toContain("typecheck");
  });

  test("parses runId string to int before passing to octokit", async () => {
    const listJobsMock = mock(async () => ({ data: { jobs: [] } }));
    const octokitMock = makeOctokitMock({ actions: { listJobsForWorkflowRun: listJobsMock } });
    const client = makeClientWithOctokit(octokitMock);

    await client.getCheckRunLogs("99876");

    expect(listJobsMock).toHaveBeenCalledWith(
      expect.objectContaining({ run_id: 99876 })
    );
  });
});

describe("GitHubOctokitClient — getPRBranch", () => {
  test("returns head.ref from the PR", async () => {
    const octokitMock = makeOctokitMock({
      pulls: {
        get: mock(async () => ({
          data: {
            number: 55,
            title: "My PR",
            state: "open",
            merged: false,
            head: { ref: "feature/TOS-55-auth" },
            base: { ref: "main" },
          },
        })),
      },
    });

    const client = makeClientWithOctokit(octokitMock);
    const branch = await client.getPRBranch(55);
    expect(branch).toBe("feature/TOS-55-auth");
  });

  test("throws when PR not found", async () => {
    const octokitMock = makeOctokitMock({
      pulls: {
        get: mock(async () => {
          const err = new Error("Not Found") as Error & { status: number };
          err.status = 404;
          throw err;
        }),
      },
    });

    const client = makeClientWithOctokit(octokitMock);
    await expect(client.getPRBranch(9999)).rejects.toThrow("PR #9999 not found");
  });
});

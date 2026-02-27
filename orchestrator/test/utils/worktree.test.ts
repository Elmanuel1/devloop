import { describe, test, expect, mock, beforeEach } from "bun:test";
import { createWorktree, removeWorktree } from "../../src/utils/worktree.ts";

// ─── Mock Bun.spawn ───────────────────────────────────────────────────────────
//
// We replace Bun.spawn with a mock that returns a fake subprocess.
// The mock captures the args and cwd so we can assert on them.

interface SpawnCall {
  args: string[];
  options: { cwd?: string; stdin?: string; stdout?: string; stderr?: string };
}

let spawnCalls: SpawnCall[] = [];
let nextExitCode = 0;
let nextStderr = "";

function makeFakeProc(exitCode: number, stderrText: string) {
  return {
    exited: Promise.resolve(exitCode),
    stdout: new ReadableStream(),
    stderr: new ReadableStream({
      start(controller) {
        if (stderrText) {
          controller.enqueue(new TextEncoder().encode(stderrText));
        }
        controller.close();
      },
    }),
    stdin: {
      getWriter: () => ({
        write: async () => {},
        close: async () => {},
      }),
    },
    kill: () => {},
  };
}

// Install the mock before each test
beforeEach(() => {
  spawnCalls = [];
  nextExitCode = 0;
  nextStderr = "";

  // @ts-expect-error — intentionally replacing Bun.spawn for testing
  Bun.spawn = (args: string[], options: SpawnCall["options"]) => {
    spawnCalls.push({ args, options });
    return makeFakeProc(nextExitCode, nextStderr);
  };
});

// ─── createWorktree ───────────────────────────────────────────────────────────

describe("createWorktree", () => {
  test("returns the correct worktree path", async () => {
    const branch = "feature/TOS-42-payments";
    const cwd = "/repo";

    const path = await createWorktree(branch, cwd);

    expect(path).toBe(`/repo/.claude/worktrees/${branch}`);
  });

  test("calls git worktree add with correct arguments", async () => {
    const branch = "feature/TOS-50-auth";
    const cwd = "/my-project";

    await createWorktree(branch, cwd);

    expect(spawnCalls).toHaveLength(1);
    const call = spawnCalls[0];
    expect(call.args).toEqual([
      "git",
      "worktree",
      "add",
      "-b",
      branch,
      `.claude/worktrees/${branch}`,
    ]);
    expect(call.options.cwd).toBe(cwd);
  });

  test("defaults cwd to process.cwd() when not provided", async () => {
    const branch = "feature/default-cwd";

    await createWorktree(branch);

    expect(spawnCalls).toHaveLength(1);
    expect(spawnCalls[0].options.cwd).toBe(process.cwd());
  });

  test("throws when git exits with non-zero code", async () => {
    nextExitCode = 128;
    nextStderr = "fatal: branch already exists";

    await expect(createWorktree("bad-branch", "/repo")).rejects.toThrow(
      "git worktree add failed (exit 128)"
    );
  });

  test("error message includes stderr output", async () => {
    nextExitCode = 1;
    nextStderr = "some git error";

    let caught: Error | undefined;
    try {
      await createWorktree("any-branch", "/repo");
    } catch (err) {
      caught = err as Error;
    }

    expect(caught).toBeDefined();
    expect(caught!.message).toContain("some git error");
  });
});

// ─── removeWorktree ───────────────────────────────────────────────────────────

describe("removeWorktree", () => {
  test("calls git worktree remove with correct arguments", async () => {
    const worktreePath = "/repo/.claude/worktrees/feature/TOS-42-payments";

    await removeWorktree(worktreePath);

    expect(spawnCalls).toHaveLength(1);
    const call = spawnCalls[0];
    expect(call.args).toEqual([
      "git",
      "worktree",
      "remove",
      worktreePath,
      "--force",
    ]);
  });

  test("throws when git exits with non-zero code", async () => {
    nextExitCode = 1;
    nextStderr = "fatal: worktree not found";

    await expect(removeWorktree("/nonexistent")).rejects.toThrow(
      "git worktree remove failed (exit 1)"
    );
  });

  test("error message includes stderr output", async () => {
    nextExitCode = 2;
    nextStderr = "worktree is dirty";

    let caught: Error | undefined;
    try {
      await removeWorktree("/some/path");
    } catch (err) {
      caught = err as Error;
    }

    expect(caught).toBeDefined();
    expect(caught!.message).toContain("worktree is dirty");
  });
});

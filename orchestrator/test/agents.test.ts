import { describe, test, expect, beforeEach } from "bun:test";
import { claudeAgent } from "../src/agents.ts";

// ─── Test Doubles ─────────────────────────────────────────────────────────────
//
// We mock Bun.file and Bun.spawn so no real filesystem or process is touched.
//
// Control variables per-test:
//   fakeExitCode     : exit code the spawned process returns
//   fakeStdout       : bytes the process writes to stdout
//   agentFileExists  : whether .claude/agents/{name}.md exists
//   heartbeatShouldFire : when true, process never emits stdout (hung)

interface FakeSpawnCall {
  args: string[];
  cwd: string;
}

let spawnCalls: FakeSpawnCall[] = [];
let fakeExitCode = 0;
let fakeStdout = "";
let agentFileExists = true;
let heartbeatShouldFire = false;

// Keep a reference to the original Bun.file so the mock can delegate
// non-agent-spec paths to it (avoids breaking process.stdout internals).
const originalBunFile = Bun.file.bind(Bun);

function makeFakeStdin() {
  return {
    write: (_data: string | ArrayBufferView | ArrayBuffer | SharedArrayBuffer) => 0,
    end: (_error?: Error) => 0,
    flush: () => 0,
    start: () => {},
    ref: () => {},
    unref: () => {},
  };
}

function makeFakeProc(exitCode: number, stdoutText: string, neverEmit = false) {
  const stdout = new ReadableStream<Uint8Array>({
    async start(controller) {
      if (neverEmit) {
        // Never emits — heartbeat or hard timeout fires first.
        // Keep stream open so the reader keeps waiting.
        await new Promise<void>((resolve) => setTimeout(resolve, 120_000));
        controller.close();
        return;
      }
      if (stdoutText) {
        controller.enqueue(new TextEncoder().encode(stdoutText));
      }
      controller.close();
    },
  });

  const stderr = new ReadableStream<Uint8Array>({
    start(controller) {
      controller.close();
    },
  });

  let killed = false;

  return {
    exited: neverEmit
      ? new Promise<number>((resolve) => setTimeout(() => resolve(exitCode), 120_000))
      : Promise.resolve(exitCode),
    stdout,
    stderr,
    stdin: makeFakeStdin(),
    kill: () => { killed = true; },
    wasKilled: () => killed,
  };
}

beforeEach(() => {
  spawnCalls = [];
  fakeExitCode = 0;
  fakeStdout = "";
  agentFileExists = true;
  heartbeatShouldFire = false;

  // Mock Bun.file — delegates non-agent paths to the original implementation
  // so that process.stdout / process.stderr internals continue to work.
  // @ts-expect-error — intentionally replacing Bun.file for testing
  Bun.file = (path: string | number | URL, options?: BlobPropertyBag) => {
    if (typeof path === "string" && path.includes(".claude/agents/")) {
      return {
        exists: async () => agentFileExists,
        text: async () => "# Agent Spec\n\nYou are a helpful agent.",
      };
    }
    // Delegate everything else to the real Bun.file
    return originalBunFile(path as string, options);
  };

  // Mock Bun.spawn — controls spawned process behaviour
  // @ts-expect-error — intentionally replacing Bun.spawn
  Bun.spawn = (args: string[], opts: { cwd?: string }) => {
    spawnCalls.push({ args, cwd: opts?.cwd ?? "" });
    return makeFakeProc(fakeExitCode, fakeStdout, heartbeatShouldFire);
  };
});

// ─── Success cases ────────────────────────────────────────────────────────────

describe("claudeAgent — success case", () => {
  test("returns success=true and parsed output when claude exits 0", async () => {
    const payload = {
      result: "Task complete",
      cost_usd: 0.03,
      duration_ms: 5000,
      num_turns: 2,
      is_error: false,
      session_id: "sess-001",
    };
    fakeStdout = JSON.stringify(payload);

    const result = await claudeAgent("architect", "Do something");

    expect(result.success).toBe(true);
    expect(result.output).toBe(fakeStdout);
    expect(result.parsed).toBeDefined();
    expect(result.parsed!.result).toBe("Task complete");
    expect(result.parsed!.cost_usd).toBe(0.03);
    expect(result.parsed!.num_turns).toBe(2);
    expect(result.parsed!.is_error).toBe(false);
    expect(result.parsed!.session_id).toBe("sess-001");
    expect(typeof result.durationMs).toBe("number");
    expect(result.durationMs).toBeGreaterThanOrEqual(0);
  });

  test("spawns claude with -p and --output-format json flags", async () => {
    fakeStdout = JSON.stringify({ result: "ok" });

    await claudeAgent("code-writer", "Build it");

    expect(spawnCalls.length).toBeGreaterThanOrEqual(1);
    const claudeCall = spawnCalls.find((c) => c.args.includes("claude"));
    expect(claudeCall).toBeDefined();
    expect(claudeCall!.args).toContain("-p");
    expect(claudeCall!.args).toContain("--output-format");
    expect(claudeCall!.args).toContain("json");
  });

  test("passes allowedTools flag when opts.allowedTools is set", async () => {
    fakeStdout = JSON.stringify({ result: "ok" });

    await claudeAgent("code-writer", "Build it", {
      allowedTools: ["Bash", "Read"],
    });

    const claudeCall = spawnCalls.find((c) => c.args.includes("claude"));
    expect(claudeCall).toBeDefined();
    expect(claudeCall!.args).toContain("--allowedTools");
    const idx = claudeCall!.args.indexOf("--allowedTools");
    expect(claudeCall!.args[idx + 1]).toBe("Bash,Read");
  });

  test("does NOT pass allowedTools flag when opts.allowedTools is empty", async () => {
    fakeStdout = JSON.stringify({ result: "ok" });

    await claudeAgent("code-writer", "Build it", { allowedTools: [] });

    const claudeCall = spawnCalls.find((c) => c.args.includes("claude"));
    expect(claudeCall).toBeDefined();
    expect(claudeCall!.args).not.toContain("--allowedTools");
  });

  test("returns success=false when claude exits non-zero", async () => {
    fakeExitCode = 1;
    fakeStdout = JSON.stringify({ result: "error output", is_error: true });

    const result = await claudeAgent("architect", "Do something");

    expect(result.success).toBe(false);
  });

  test("durationMs is a non-negative number", async () => {
    fakeStdout = JSON.stringify({ result: "done" });

    const result = await claudeAgent("architect", "Go");

    expect(result.durationMs).toBeGreaterThanOrEqual(0);
  });
});

// ─── Missing agent file ───────────────────────────────────────────────────────

describe("claudeAgent — missing agent file", () => {
  test("throws an error when agent spec file does not exist", async () => {
    agentFileExists = false;

    await expect(claudeAgent("nonexistent-agent", "prompt")).rejects.toThrow(
      "Agent spec not found"
    );
  });

  test("claude is NOT spawned when agent file is missing", async () => {
    agentFileExists = false;

    try {
      await claudeAgent("nonexistent-agent", "prompt");
    } catch {
      // expected
    }

    const claudeCall = spawnCalls.find((c) => c.args.includes("claude"));
    expect(claudeCall).toBeUndefined();
  });

  test("error message includes the agent name", async () => {
    agentFileExists = false;

    let caught: Error | undefined;
    try {
      await claudeAgent("missing-bot", "prompt");
    } catch (err) {
      caught = err as Error;
    }

    expect(caught).toBeDefined();
    expect(caught!.message).toContain("missing-bot");
  });
});

// ─── Heartbeat watchdog ───────────────────────────────────────────────────────

describe("claudeAgent — heartbeat watchdog", () => {
  test("returns success=false when no stdout for heartbeatMs", async () => {
    // Process never emits stdout → heartbeat fires → process killed
    heartbeatShouldFire = true;

    const result = await claudeAgent("architect", "Hang forever", {
      heartbeatMs: 50,
      timeoutMs: 5000, // hard timeout is longer than heartbeat
    });

    expect(result.success).toBe(false);
  });

  test("durationMs is at least heartbeatMs", async () => {
    heartbeatShouldFire = true;

    const result = await claudeAgent("architect", "Hang", {
      heartbeatMs: 50,
      timeoutMs: 5000,
    });

    expect(result.durationMs).toBeGreaterThanOrEqual(50);
  });
});

// ─── Hard timeout ─────────────────────────────────────────────────────────────

describe("claudeAgent — hard timeout", () => {
  test("rejects with timeout error when process exceeds timeoutMs", async () => {
    // timeoutMs shorter than heartbeatMs → hard timeout fires first
    heartbeatShouldFire = true;

    await expect(
      claudeAgent("architect", "Never finish", {
        timeoutMs: 50,
        heartbeatMs: 10000,
      })
    ).rejects.toThrow("exceeded hard timeout");
  });
});

// ─── Worktree option ──────────────────────────────────────────────────────────

describe("claudeAgent — worktree option", () => {
  test("when opts.worktree is not set, no git commands are spawned", async () => {
    fakeStdout = JSON.stringify({ result: "ok" });

    await claudeAgent("architect", "some prompt");

    const gitCall = spawnCalls.find((c) => c.args[0] === "git");
    expect(gitCall).toBeUndefined();
  });

  test("when opts.worktree=false, no git commands are spawned", async () => {
    fakeStdout = JSON.stringify({ result: "ok" });

    await claudeAgent("architect", "some prompt", { worktree: false });

    const gitCall = spawnCalls.find((c) => c.args[0] === "git");
    expect(gitCall).toBeUndefined();
  });

  test("when opts.worktree=true, spawns git worktree add before claude", async () => {
    fakeStdout = JSON.stringify({ result: "ok" });

    await claudeAgent("architect", "some prompt", {
      worktree: true,
      branch: "feature/test-branch",
      cwd: "/repo",
    });

    const gitAddCall = spawnCalls.find(
      (c) => c.args[0] === "git" && c.args[1] === "worktree" && c.args[2] === "add"
    );
    expect(gitAddCall).toBeDefined();
    expect(gitAddCall!.args).toContain("feature/test-branch");
  });

  test("when opts.worktree=true and keepWorktree=false, spawns git worktree remove in finally", async () => {
    fakeStdout = JSON.stringify({ result: "ok" });

    await claudeAgent("architect", "some prompt", {
      worktree: true,
      branch: "feature/test-branch",
      keepWorktree: false,
      cwd: "/repo",
    });

    const gitRemoveCall = spawnCalls.find(
      (c) => c.args[0] === "git" && c.args[1] === "worktree" && c.args[2] === "remove"
    );
    expect(gitRemoveCall).toBeDefined();
  });

  test("when opts.worktree=true and keepWorktree=true, does NOT spawn git worktree remove", async () => {
    fakeStdout = JSON.stringify({ result: "ok" });

    await claudeAgent("architect", "some prompt", {
      worktree: true,
      branch: "feature/keep-me",
      keepWorktree: true,
      cwd: "/repo",
    });

    const gitRemoveCall = spawnCalls.find(
      (c) => c.args[0] === "git" && c.args[1] === "worktree" && c.args[2] === "remove"
    );
    expect(gitRemoveCall).toBeUndefined();
  });
});

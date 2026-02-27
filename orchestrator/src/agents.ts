import type { AgentOptions, AgentResult } from "./types.ts";
import { parseAgentOutput } from "./utils/parse-agent-output.ts";
import { createWorktree, removeWorktree } from "./utils/worktree.ts";
import { config } from "./config.ts";
import log from "./utils/logger.ts";

/**
 * Pure agent runner — spawns `claude -p --output-format json --agent {agent}`,
 * passes the prompt via stdin, enforces a 1-hour hard timeout and a 10-minute
 * heartbeat watchdog, then returns the parsed result.
 *
 * Claude Code automatically loads `.claude/agents/{agent}.md` via the --agent
 * flag. NO business logic lives here. Callers decide what to do with the result.
 */
export async function claudeAgent(
  agent: string,
  prompt: string,
  opts?: AgentOptions
): Promise<AgentResult> {
  const timeoutMs = opts?.timeoutMs ?? config.agentTimeoutMs;
  const heartbeatMs = opts?.heartbeatMs ?? config.agentHeartbeatMs;
  const cwd = opts?.cwd ?? process.cwd();

  let worktreePath: string | undefined;
  let spawnCwd = cwd;

  if (opts?.worktree === true) {
    const branch = opts.branch ?? `agent/${agent}-${Date.now()}`;
    worktreePath = await createWorktree(branch, cwd);
    spawnCwd = worktreePath;
    log.info("Worktree created", { agent, branch, worktreePath });
  }

  try {
    return await runAgent(
      agent,
      prompt,
      spawnCwd,
      timeoutMs,
      heartbeatMs,
      opts?.allowedTools
    );
  } finally {
    if (worktreePath !== undefined && opts?.keepWorktree !== true) {
      try {
        await removeWorktree(worktreePath);
        log.info("Worktree removed", { agent, worktreePath });
      } catch (err) {
        log.warn("Failed to remove worktree", { agent, worktreePath, err: String(err) });
      }
    }
  }
}

// ─── Outcome discriminated union ─────────────────────────────────────────────

type RunOutcome =
  | { kind: "completed"; exitCode: number; output: string }
  | { kind: "heartbeat"; output: string }
  | { kind: "timeout" };

/**
 * Spawns the claude CLI process, enforces timeout + heartbeat, and returns
 * the AgentResult. Extracted to keep claudeAgent readable.
 */
async function runAgent(
  agent: string,
  prompt: string,
  cwd: string,
  timeoutMs: number,
  heartbeatMs: number,
  allowedTools?: string[]
): Promise<AgentResult> {
  const startMs = Date.now();

  const args = ["claude", "-p", "--output-format", "json", "--agent", agent];

  if (allowedTools !== undefined && allowedTools.length > 0) {
    args.push("--allowedTools", allowedTools.join(","));
  }

  const proc = Bun.spawn(args, {
    cwd,
    stdin: "pipe",
    stdout: "pipe",
    stderr: "pipe",
  });

  // Write the prompt to stdin and close the stream.
  // Bun.spawn with stdin:"pipe" returns a FileSink (not a WHATWG WritableStream).
  proc.stdin.write(prompt);
  proc.stdin.end();

  // ─── Outcome promise ─────────────────────────────────────────────────────────
  //
  // One of three things settles this promise first:
  //   1. The process exits normally → completed
  //   2. No stdout for heartbeatMs → heartbeat (process killed)
  //   3. timeoutMs elapsed → timeout (hard reject)

  const stdoutChunks: Uint8Array[] = [];

  let resolveOutcome!: (outcome: RunOutcome) => void;
  let rejectOutcome!: (err: Error) => void;

  const outcomePromise = new Promise<RunOutcome>((resolve, reject) => {
    resolveOutcome = resolve;
    rejectOutcome = reject;
  });

  // Drain stdout + heartbeat tracking
  let heartbeatTimer: ReturnType<typeof setTimeout> | undefined;
  let drainDone = false;

  function collectOutput(): string {
    const total = stdoutChunks.reduce((s, c) => s + c.byteLength, 0);
    const combined = new Uint8Array(total);
    let off = 0;
    for (const c of stdoutChunks) { combined.set(c, off); off += c.byteLength; }
    return new TextDecoder().decode(combined);
  }

  function cancelHeartbeat(): void {
    if (heartbeatTimer !== undefined) {
      clearTimeout(heartbeatTimer);
      heartbeatTimer = undefined;
    }
  }

  function armHeartbeat(): void {
    cancelHeartbeat();
    heartbeatTimer = setTimeout(() => {
      if (drainDone) return;
      drainDone = true;
      log.warn("Agent heartbeat timeout — killing process", { agent, heartbeatMs });
      try { proc.kill(); } catch { /* already dead */ }
      resolveOutcome({ kind: "heartbeat", output: collectOutput() });
    }, heartbeatMs);
  }

  // Hard timeout
  const hardTimeoutTimer = setTimeout(() => {
    if (drainDone) return;
    drainDone = true;
    cancelHeartbeat();
    log.warn("Agent hard timeout — killing process", { agent, timeoutMs });
    try { proc.kill(); } catch { /* already dead */ }
    rejectOutcome(new Error(`Agent "${agent}" exceeded hard timeout of ${timeoutMs}ms`));
  }, timeoutMs);

  // Start the heartbeat
  armHeartbeat();

  // Async drain — reads stdout, feeds heartbeat, resolves when stream closes
  const stdoutReader = proc.stdout.getReader();

  (async () => {
    try {
      while (true) {
        const { done, value } = await stdoutReader.read();
        if (done) break;
        if (value !== undefined && value.byteLength > 0) {
          stdoutChunks.push(value);
          armHeartbeat(); // reset heartbeat on each chunk
        }
      }
    } catch {
      // Stream error — treat as completion
    } finally {
      try { stdoutReader.releaseLock(); } catch { /* ignore */ }
    }

    // Stdout stream closed → wait for process exit
    if (drainDone) return; // heartbeat or timeout already resolved
    const exitCode = await proc.exited;
    if (drainDone) return; // resolved in the meantime

    drainDone = true;
    cancelHeartbeat();
    clearTimeout(hardTimeoutTimer);
    resolveOutcome({ kind: "completed", exitCode, output: collectOutput() });
  })();

  // Wait for the outcome
  const outcome = await outcomePromise;

  // Clean up timers (in case outcome resolved before they fired)
  cancelHeartbeat();
  clearTimeout(hardTimeoutTimer);

  const durationMs = Date.now() - startMs;

  switch (outcome.kind) {
    case "heartbeat":
      return { success: false, output: outcome.output, durationMs };

    case "completed": {
      const parsed = parseAgentOutput(outcome.output);
      return {
        success: outcome.exitCode === 0,
        output: outcome.output,
        parsed,
        durationMs,
      };
    }

    case "timeout":
      // This branch is unreachable — timeout rejects the promise.
      // TypeScript needs it for exhaustiveness.
      throw new Error(`Agent "${agent}" exceeded hard timeout of ${timeoutMs}ms`);
  }
}

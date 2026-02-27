/**
 * Git worktree lifecycle utilities.
 *
 * Uses `Bun.spawn` to run git commands. All paths are relative to `cwd`.
 */

/**
 * Creates a new git worktree on a new branch.
 *
 * @param branch - The branch name to create (e.g. "feature/TOS-42-payments")
 * @param cwd    - Working directory for the git command (defaults to process.cwd())
 * @returns      - The absolute path to the newly created worktree
 */
export async function createWorktree(branch: string, cwd?: string): Promise<string> {
  const workingDir = cwd ?? process.cwd();
  const worktreePath = `.claude/worktrees/${branch}`;

  const proc = Bun.spawn(
    ["git", "worktree", "add", "-b", branch, worktreePath],
    {
      cwd: workingDir,
      stdout: "pipe",
      stderr: "pipe",
    }
  );

  const exitCode = await proc.exited;

  if (exitCode !== 0) {
    const stderr = await new Response(proc.stderr).text();
    throw new Error(`git worktree add failed (exit ${exitCode}): ${stderr.trim()}`);
  }

  // Resolve to an absolute path
  const absolutePath = worktreePath.startsWith("/")
    ? worktreePath
    : `${workingDir}/${worktreePath}`;

  return absolutePath;
}

/**
 * Removes an existing git worktree by its path.
 *
 * @param worktreePath - Absolute or relative path to the worktree directory
 */
export async function removeWorktree(worktreePath: string): Promise<void> {
  const proc = Bun.spawn(
    ["git", "worktree", "remove", worktreePath, "--force"],
    {
      cwd: process.cwd(),
      stdout: "pipe",
      stderr: "pipe",
    }
  );

  const exitCode = await proc.exited;

  if (exitCode !== 0) {
    const stderr = await new Response(proc.stderr).text();
    throw new Error(`git worktree remove failed (exit ${exitCode}): ${stderr.trim()}`);
  }
}

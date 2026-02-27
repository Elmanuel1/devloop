import { Octokit } from "@octokit/rest";
import type { GitHubClient, GitHubPR, GitHubReviewComment } from "../types.ts";

export interface GitHubConfig {
  token: string;
  owner: string;
  repo: string;
}

export class GitHubOctokitClient implements GitHubClient {
  private readonly octokit: Octokit;
  private readonly owner: string;
  private readonly repo: string;

  constructor(cfg: GitHubConfig) {
    this.octokit = new Octokit({ auth: cfg.token });
    this.owner = cfg.owner;
    this.repo = cfg.repo;
  }

  async getPR(prNumber: number): Promise<GitHubPR | null> {
    try {
      const { data } = await this.octokit.pulls.get({
        owner: this.owner,
        repo: this.repo,
        pull_number: prNumber,
      });

      return {
        number: data.number,
        title: data.title,
        state: data.state,
        merged: data.merged ?? false,
        head: { ref: data.head.ref },
        base: { ref: data.base.ref },
      };
    } catch (err: unknown) {
      if (isNotFound(err)) {
        return null;
      }
      throw err;
    }
  }

  async findPR(branch: string): Promise<GitHubPR | null> {
    const { data } = await this.octokit.pulls.list({
      owner: this.owner,
      repo: this.repo,
      head: `${this.owner}:${branch}`,
      state: "open",
    });

    if (!data.length) {
      return null;
    }

    const pr = data[0];
    return {
      number: pr.number,
      title: pr.title,
      state: pr.state,
      merged: false,
      head: { ref: pr.head.ref },
      base: { ref: pr.base.ref },
    };
  }

  async mergePR(prNumber: number, method: "merge" | "squash" | "rebase" = "squash"): Promise<void> {
    await this.octokit.pulls.merge({
      owner: this.owner,
      repo: this.repo,
      pull_number: prNumber,
      merge_method: method,
    });
  }

  async getPRReviewComments(prNumber: number): Promise<GitHubReviewComment[]> {
    const { data } = await this.octokit.pulls.listReviewComments({
      owner: this.owner,
      repo: this.repo,
      pull_number: prNumber,
    });

    return data.map((comment) => ({
      id: comment.id,
      body: comment.body,
      path: comment.path,
      line: comment.line ?? undefined,
      user: { login: comment.user?.login ?? "" },
    }));
  }

  async getCheckRunLogs(runId: string): Promise<string> {
    const { data } = await this.octokit.actions.listJobsForWorkflowRun({
      owner: this.owner,
      repo: this.repo,
      run_id: parseInt(runId, 10),
    });

    const lines: string[] = [];

    for (const job of data.jobs) {
      lines.push(`Job: ${job.name} — ${job.conclusion ?? job.status}`);
      if (job.steps) {
        for (const step of job.steps) {
          lines.push(`  Step: ${step.name} — ${step.conclusion ?? step.status}`);
        }
      }
    }

    return lines.join("\n");
  }

  async getPRBranch(prNumber: number): Promise<string> {
    const pr = await this.getPR(prNumber);
    if (!pr) {
      throw new Error(`PR #${prNumber} not found`);
    }
    return pr.head.ref;
  }
}

function isNotFound(err: unknown): boolean {
  return (
    typeof err === "object" &&
    err !== null &&
    "status" in err &&
    (err as { status: number }).status === 404
  );
}

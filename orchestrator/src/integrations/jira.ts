import type { JiraClient, JiraIssueFields } from "../types.ts";

export interface JiraConfig {
  baseUrl: string;
  email: string;
  apiToken: string;
  projectKey: string;
}

export class JiraRestClient implements JiraClient {
  private readonly baseUrl: string;
  private readonly authHeader: string;
  private readonly projectKey: string;

  constructor(cfg: JiraConfig) {
    this.baseUrl = cfg.baseUrl.replace(/\/$/, "");
    this.projectKey = cfg.projectKey;
    const credentials = `${cfg.email}:${cfg.apiToken}`;
    this.authHeader = `Basic ${Buffer.from(credentials).toString("base64")}`;
  }

  private async request<T>(method: string, path: string, body?: unknown): Promise<T> {
    const url = `${this.baseUrl}${path}`;
    const headers: Record<string, string> = {
      Authorization: this.authHeader,
      "Content-Type": "application/json",
      Accept: "application/json",
    };

    const res = await fetch(url, {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });

    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`Jira API error ${res.status} ${res.statusText}: ${text}`);
    }

    // Some Jira endpoints return 204 No Content
    if (res.status === 204) {
      return undefined as unknown as T;
    }

    return res.json() as Promise<T>;
  }

  async createIssue(fields: JiraIssueFields): Promise<{ key: string; id: string }> {
    const body = {
      fields: {
        project: { key: this.projectKey },
        ...fields,
      },
    };
    return this.request<{ key: string; id: string }>("POST", "/rest/api/3/issue", body);
  }

  async createSubTask(parentKey: string, fields: JiraIssueFields): Promise<{ key: string; id: string }> {
    const body = {
      fields: {
        project: { key: this.projectKey },
        issuetype: { name: "Sub-task" },
        parent: { key: parentKey },
        ...fields,
        // Ensure issuetype is Sub-task regardless of caller
        ...(fields.issuetype ? {} : {}),
      },
    };
    // Override issuetype to Sub-task
    (body.fields as Record<string, unknown>).issuetype = { name: "Sub-task" };
    return this.request<{ key: string; id: string }>("POST", "/rest/api/3/issue", body);
  }

  async getSubTasks(parentKey: string): Promise<Array<{ key: string; summary: string }>> {
    const jql = encodeURIComponent(`parent=${parentKey}`);
    const data = await this.request<{
      issues: Array<{ key: string; fields: { summary: string } }>;
    }>("GET", `/rest/api/3/search?jql=${jql}`);

    return data.issues.map((issue) => ({
      key: issue.key,
      summary: issue.fields.summary,
    }));
  }

  async transition(issueKey: string, transitionName: string): Promise<void> {
    const data = await this.request<{
      transitions: Array<{ id: string; name: string }>;
    }>("GET", `/rest/api/3/issue/${issueKey}/transitions`);

    const found = data.transitions.find(
      (t) => t.name.toLowerCase() === transitionName.toLowerCase()
    );

    if (!found) {
      throw new Error(
        `Jira transition "${transitionName}" not found for issue ${issueKey}. Available: ${data.transitions.map((t) => t.name).join(", ")}`
      );
    }

    await this.request<void>("POST", `/rest/api/3/issue/${issueKey}/transitions`, {
      transition: { id: found.id },
    });
  }

  async addComment(issueKey: string, body: string): Promise<void> {
    await this.request<unknown>("POST", `/rest/api/3/issue/${issueKey}/comment`, {
      body: {
        type: "doc",
        version: 1,
        content: [
          {
            type: "paragraph",
            content: [{ type: "text", text: body }],
          },
        ],
      },
    });
  }
}

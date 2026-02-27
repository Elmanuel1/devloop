import type {
  ConfluenceClient,
  ConfluencePage,
  ConfluenceComment,
} from "../types.ts";

export interface ConfluenceConfig {
  baseUrl: string;
  email: string;
  apiToken: string;
  spaceKey: string;
}

interface ConfluencePageApiResponse {
  id: string;
  title: string;
  version: { number: number };
  body?: { storage?: { value?: string } };
}

interface ConfluencePageListResponse {
  results: ConfluencePageApiResponse[];
}

interface ConfluencePropertyResponse {
  key: string;
  value: unknown;
}

interface ConfluenceFooterCommentResponse {
  id: string;
  body: { storage: { value: string } };
  version: { createdAt: string };
  createdBy?: { displayName?: string; publicName?: string };
}

interface ConfluenceFooterCommentListResponse {
  results: ConfluenceFooterCommentResponse[];
}

export class ConfluenceRestClient implements ConfluenceClient {
  private readonly baseUrl: string;
  private readonly authHeader: string;
  private readonly spaceKey: string;

  constructor(cfg: ConfluenceConfig) {
    this.baseUrl = cfg.baseUrl.replace(/\/$/, "");
    this.spaceKey = cfg.spaceKey;
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
      throw new Error(`Confluence API error ${res.status} ${res.statusText}: ${text}`);
    }

    if (res.status === 204) {
      return undefined as unknown as T;
    }

    return res.json() as Promise<T>;
  }

  private mapPage(page: ConfluencePageApiResponse): ConfluencePage {
    return {
      id: page.id,
      title: page.title,
      version: page.version.number,
      body: page.body?.storage?.value,
    };
  }

  async createPage(title: string, body: string, parentId?: string): Promise<ConfluencePage> {
    const requestBody: Record<string, unknown> = {
      spaceId: this.spaceKey,
      status: "current",
      title,
      body: {
        representation: "storage",
        value: body,
      },
    };

    if (parentId) {
      requestBody.parentId = parentId;
    }

    const page = await this.request<ConfluencePageApiResponse>("POST", "/api/v2/pages", requestBody);
    return this.mapPage(page);
  }

  async updatePage(pageId: string, title: string, body: string, version: number): Promise<ConfluencePage> {
    const requestBody = {
      id: pageId,
      status: "current",
      title,
      body: {
        representation: "storage",
        value: body,
      },
      version: {
        number: version,
        message: "",
      },
    };

    const page = await this.request<ConfluencePageApiResponse>("PUT", `/api/v2/pages/${pageId}`, requestBody);
    return this.mapPage(page);
  }

  async findPage(title: string): Promise<ConfluencePage | null> {
    const params = new URLSearchParams({
      title,
      spaceKey: this.spaceKey,
    });

    const data = await this.request<ConfluencePageListResponse>(
      "GET",
      `/api/v2/pages?${params.toString()}`
    );

    if (!data.results.length) {
      return null;
    }

    return this.mapPage(data.results[0]);
  }

  async getContentState(pageId: string): Promise<string | null> {
    try {
      const prop = await this.request<ConfluencePropertyResponse>(
        "GET",
        `/api/v2/pages/${pageId}/properties/content-state`
      );
      return typeof prop.value === "string" ? prop.value : null;
    } catch {
      return null;
    }
  }

  async setContentState(pageId: string, key: string, value: string): Promise<void> {
    const body = {
      key,
      value,
    };

    try {
      // Try PUT (update) first
      await this.request<unknown>("PUT", `/api/v2/pages/${pageId}/properties/${key}`, body);
    } catch {
      // If not found, create via POST
      await this.request<unknown>("POST", `/api/v2/pages/${pageId}/properties`, body);
    }
  }

  async getPagesInReview(): Promise<ConfluencePage[]> {
    const params = new URLSearchParams({
      spaceKey: this.spaceKey,
    });

    const data = await this.request<ConfluencePageListResponse>(
      "GET",
      `/api/v2/pages?${params.toString()}`
    );

    const pages = await Promise.all(
      data.results.map(async (page) => {
        const state = await this.getContentState(page.id);
        return { page, state };
      })
    );

    return pages
      .filter(({ state }) => state === "In Review")
      .map(({ page }) => this.mapPage(page));
  }

  async getNewComments(pageId: string, since: string): Promise<ConfluenceComment[]> {
    const [footerData, inlineData] = await Promise.all([
      this.request<ConfluenceFooterCommentListResponse>(
        "GET",
        `/api/v2/pages/${pageId}/footer-comments`
      ),
      this.request<ConfluenceFooterCommentListResponse>(
        "GET",
        `/api/v2/pages/${pageId}/inline-comments`
      ),
    ]);

    const sinceDate = new Date(since);
    const allComments = [...footerData.results, ...inlineData.results];

    return allComments
      .filter((comment) => new Date(comment.version.createdAt) > sinceDate)
      .map((comment) => ({
        id: comment.id,
        body: comment.body.storage.value,
        author: comment.createdBy?.displayName ?? comment.createdBy?.publicName ?? "unknown",
        createdAt: comment.version.createdAt,
      }));
  }
}

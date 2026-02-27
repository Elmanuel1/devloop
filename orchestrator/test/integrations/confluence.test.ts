import { describe, test, expect, beforeEach, mock, afterEach } from "bun:test";
import { ConfluenceRestClient } from "../../src/integrations/confluence.ts";

const BASE_URL = "https://example.atlassian.net/wiki";
const EMAIL = "user@example.com";
const API_TOKEN = "test-token";
const SPACE_KEY = "TOS";

function makeClient() {
  return new ConfluenceRestClient({ baseUrl: BASE_URL, email: EMAIL, apiToken: API_TOKEN, spaceKey: SPACE_KEY });
}

function pageResponse(overrides: Record<string, unknown> = {}) {
  return {
    id: "123456",
    title: "Test Page",
    version: { number: 1 },
    body: { storage: { value: "<p>body</p>" } },
    ...overrides,
  };
}

describe("ConfluenceRestClient — createPage", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("POSTs to /api/v2/pages with title, body, and spaceId", async () => {
    let capturedUrl = "";
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (url: string, opts?: RequestInit) => {
      capturedUrl = url;
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => pageResponse(),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    const result = await client.createPage("Test Page", "<p>body</p>");

    expect(capturedUrl).toBe(`${BASE_URL}/api/v2/pages`);
    const body = capturedBody as Record<string, unknown>;
    expect(body.title).toBe("Test Page");
    expect(body.spaceId).toBe(SPACE_KEY);
    expect((body.body as Record<string, unknown>).value).toBe("<p>body</p>");

    expect(result.id).toBe("123456");
    expect(result.title).toBe("Test Page");
    expect(result.version).toBe(1);
  });

  test("includes parentId when provided", async () => {
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (_url: string, opts?: RequestInit) => {
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => pageResponse(),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.createPage("Child Page", "<p>child</p>", "parent-99");

    const body = capturedBody as Record<string, unknown>;
    expect(body.parentId).toBe("parent-99");
  });

  test("throws on non-2xx response", async () => {
    globalThis.fetch = mock(async () => ({
      ok: false,
      status: 403,
      statusText: "Forbidden",
      json: async () => ({ message: "Not allowed" }),
      text: async () => "Not allowed",
    })) as typeof fetch;

    const client = makeClient();
    await expect(client.createPage("Title", "body")).rejects.toThrow("Confluence API error 403");
  });
});

describe("ConfluenceRestClient — updatePage", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("PUTs to /api/v2/pages/{id} with full body replace and version", async () => {
    let capturedUrl = "";
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (url: string, opts?: RequestInit) => {
      capturedUrl = url;
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => pageResponse({ version: { number: 2 } }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    const result = await client.updatePage("123456", "Updated Title", "<p>new content</p>", 2);

    expect(capturedUrl).toBe(`${BASE_URL}/api/v2/pages/123456`);
    const body = capturedBody as Record<string, unknown>;
    expect(body.title).toBe("Updated Title");
    expect((body.version as { number: number }).number).toBe(2);
    expect(result.version).toBe(2);
  });
});

describe("ConfluenceRestClient — findPage", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("GETs /api/v2/pages with title and spaceKey params and returns first result", async () => {
    let capturedUrl = "";

    globalThis.fetch = mock(async (url: string) => {
      capturedUrl = url;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({ results: [pageResponse()] }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    const result = await client.findPage("Test Page");

    expect(capturedUrl).toContain("/api/v2/pages");
    expect(capturedUrl).toContain("title=Test+Page");
    expect(capturedUrl).toContain(`spaceKey=${SPACE_KEY}`);
    expect(result).not.toBeNull();
    expect(result!.id).toBe("123456");
  });

  test("returns null when no pages found", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({ results: [] }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.findPage("Missing Page");
    expect(result).toBeNull();
  });
});

describe("ConfluenceRestClient — getContentState", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("GETs page property and returns value as string", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({ key: "content-state", value: "In Review" }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.getContentState("123456");
    expect(result).toBe("In Review");
  });

  test("returns null when property fetch fails (e.g. 404)", async () => {
    globalThis.fetch = mock(async () => ({
      ok: false,
      status: 404,
      statusText: "Not Found",
      json: async () => ({ message: "Property not found" }),
      text: async () => "Not found",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.getContentState("123456");
    expect(result).toBeNull();
  });

  test("returns null when value is not a string", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({ key: "content-state", value: { nested: "obj" } }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.getContentState("123456");
    expect(result).toBeNull();
  });
});

describe("ConfluenceRestClient — setContentState", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("PUTs property when update succeeds", async () => {
    let capturedMethod = "";
    let capturedUrl = "";

    globalThis.fetch = mock(async (url: string, opts?: RequestInit) => {
      capturedMethod = opts?.method ?? "GET";
      capturedUrl = url;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({ key: "status", value: "Approved" }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.setContentState("123456", "status", "Approved");

    expect(capturedMethod).toBe("PUT");
    expect(capturedUrl).toContain("/pages/123456/properties/status");
  });

  test("POSTs when PUT fails (property does not exist yet)", async () => {
    const methods: string[] = [];

    globalThis.fetch = mock(async (_url: string, opts?: RequestInit) => {
      const method = opts?.method ?? "GET";
      methods.push(method);

      if (method === "PUT") {
        return {
          ok: false,
          status: 404,
          statusText: "Not Found",
          json: async () => ({ message: "Not found" }),
          text: async () => "Not found",
        } as Response;
      }

      return {
        ok: true,
        status: 201,
        statusText: "Created",
        json: async () => ({ key: "status", value: "Draft" }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.setContentState("123456", "status", "Draft");

    expect(methods).toContain("PUT");
    expect(methods).toContain("POST");
  });
});

describe("ConfluenceRestClient — getPagesInReview", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("returns only pages whose content state is 'In Review'", async () => {
    let callCount = 0;

    globalThis.fetch = mock(async (url: string) => {
      callCount++;

      // First call: list pages
      if (url.includes("/api/v2/pages") && !url.includes("/properties")) {
        return {
          ok: true,
          status: 200,
          statusText: "OK",
          json: async () => ({
            results: [
              pageResponse({ id: "1", title: "Page 1" }),
              pageResponse({ id: "2", title: "Page 2" }),
              pageResponse({ id: "3", title: "Page 3" }),
            ],
          }),
          text: async () => "{}",
        } as Response;
      }

      // Subsequent calls: properties per page
      // page "1" is In Review, "2" returns error (null state), "3" is Draft
      if (url.includes("/1/properties")) {
        return {
          ok: true,
          status: 200,
          statusText: "OK",
          json: async () => ({ key: "content-state", value: "In Review" }),
          text: async () => "{}",
        } as Response;
      }
      if (url.includes("/2/properties")) {
        return {
          ok: false,
          status: 404,
          statusText: "Not Found",
          json: async () => ({ message: "Not found" }),
          text: async () => "Not found",
        } as Response;
      }
      if (url.includes("/3/properties")) {
        return {
          ok: true,
          status: 200,
          statusText: "OK",
          json: async () => ({ key: "content-state", value: "Draft" }),
          text: async () => "{}",
        } as Response;
      }

      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({ key: "content-state", value: null }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    const result = await client.getPagesInReview();

    expect(result).toHaveLength(1);
    expect(result[0].id).toBe("1");
    expect(callCount).toBeGreaterThan(1);
  });
});

describe("ConfluenceRestClient — getNewComments", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("GETs footer comments and filters by since date", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({
        results: [
          {
            id: "c1",
            body: { storage: { value: "Old comment" } },
            version: { createdAt: "2024-01-01T00:00:00.000Z" },
            createdBy: { displayName: "Alice" },
          },
          {
            id: "c2",
            body: { storage: { value: "New comment" } },
            version: { createdAt: "2024-06-15T10:00:00.000Z" },
            createdBy: { displayName: "Bob" },
          },
        ],
      }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.getNewComments("123456", "2024-03-01T00:00:00.000Z");

    expect(result).toHaveLength(1);
    expect(result[0].id).toBe("c2");
    expect(result[0].body).toBe("New comment");
    expect(result[0].author).toBe("Bob");
    expect(result[0].createdAt).toBe("2024-06-15T10:00:00.000Z");
  });

  test("returns empty array when all comments are before since date", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({
        results: [
          {
            id: "c1",
            body: { storage: { value: "Old comment" } },
            version: { createdAt: "2023-01-01T00:00:00.000Z" },
            createdBy: { displayName: "Alice" },
          },
        ],
      }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.getNewComments("123456", "2024-01-01T00:00:00.000Z");
    expect(result).toHaveLength(0);
  });

  test("uses publicName when displayName not present", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({
        results: [
          {
            id: "c1",
            body: { storage: { value: "Comment" } },
            version: { createdAt: "2025-01-01T00:00:00.000Z" },
            createdBy: { publicName: "charlie99" },
          },
        ],
      }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.getNewComments("123456", "2024-01-01T00:00:00.000Z");
    expect(result[0].author).toBe("charlie99");
  });

  test("GETs from /api/v2/pages/{id}/footer-comments endpoint", async () => {
    let capturedUrl = "";

    globalThis.fetch = mock(async (url: string) => {
      capturedUrl = url;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({ results: [] }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.getNewComments("99999", "2024-01-01T00:00:00.000Z");

    expect(capturedUrl).toContain("/pages/99999/footer-comments");
  });
});

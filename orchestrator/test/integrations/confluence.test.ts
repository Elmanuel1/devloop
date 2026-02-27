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

  function makeFetchWithResponses(footerResults: unknown[], inlineResults: unknown[]) {
    return mock(async (url: string) => {
      const isInline = (url as string).includes("/inline-comments");
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({ results: isInline ? inlineResults : footerResults }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;
  }

  test("fetches both footer and inline comments in parallel", async () => {
    const capturedUrls: string[] = [];

    globalThis.fetch = mock(async (url: string) => {
      capturedUrls.push(url as string);
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

    expect(capturedUrls.some((u) => u.includes("/pages/99999/footer-comments"))).toBe(true);
    expect(capturedUrls.some((u) => u.includes("/pages/99999/inline-comments"))).toBe(true);
    expect(capturedUrls).toHaveLength(2);
  });

  test("merges footer and inline comments into a single result", async () => {
    const footerResults = [
      {
        id: "footer-1",
        body: { storage: { value: "Footer comment" } },
        version: { createdAt: "2024-06-01T00:00:00.000Z" },
        createdBy: { displayName: "Alice" },
      },
    ];
    const inlineResults = [
      {
        id: "inline-1",
        body: { storage: { value: "Inline comment" } },
        version: { createdAt: "2024-06-02T00:00:00.000Z" },
        createdBy: { displayName: "Bob" },
      },
    ];

    globalThis.fetch = makeFetchWithResponses(footerResults, inlineResults);

    const client = makeClient();
    const result = await client.getNewComments("123456", "2024-01-01T00:00:00.000Z");

    expect(result).toHaveLength(2);
    const ids = result.map((c) => c.id);
    expect(ids).toContain("footer-1");
    expect(ids).toContain("inline-1");
  });

  test("filters by since date across combined footer and inline results", async () => {
    const footerResults = [
      {
        id: "footer-old",
        body: { storage: { value: "Old footer comment" } },
        version: { createdAt: "2024-01-01T00:00:00.000Z" },
        createdBy: { displayName: "Alice" },
      },
      {
        id: "footer-new",
        body: { storage: { value: "New footer comment" } },
        version: { createdAt: "2024-06-15T10:00:00.000Z" },
        createdBy: { displayName: "Bob" },
      },
    ];
    const inlineResults = [
      {
        id: "inline-old",
        body: { storage: { value: "Old inline comment" } },
        version: { createdAt: "2024-02-01T00:00:00.000Z" },
        createdBy: { displayName: "Carol" },
      },
      {
        id: "inline-new",
        body: { storage: { value: "New inline comment" } },
        version: { createdAt: "2024-07-01T00:00:00.000Z" },
        createdBy: { displayName: "Dave" },
      },
    ];

    globalThis.fetch = makeFetchWithResponses(footerResults, inlineResults);

    const client = makeClient();
    const result = await client.getNewComments("123456", "2024-03-01T00:00:00.000Z");

    expect(result).toHaveLength(2);
    const ids = result.map((c) => c.id);
    expect(ids).toContain("footer-new");
    expect(ids).toContain("inline-new");
    expect(ids).not.toContain("footer-old");
    expect(ids).not.toContain("inline-old");
  });

  test("returns empty array when all comments from both sources are before since date", async () => {
    const footerResults = [
      {
        id: "c1",
        body: { storage: { value: "Old footer" } },
        version: { createdAt: "2023-01-01T00:00:00.000Z" },
        createdBy: { displayName: "Alice" },
      },
    ];
    const inlineResults = [
      {
        id: "c2",
        body: { storage: { value: "Old inline" } },
        version: { createdAt: "2023-06-01T00:00:00.000Z" },
        createdBy: { displayName: "Bob" },
      },
    ];

    globalThis.fetch = makeFetchWithResponses(footerResults, inlineResults);

    const client = makeClient();
    const result = await client.getNewComments("123456", "2024-01-01T00:00:00.000Z");
    expect(result).toHaveLength(0);
  });

  test("maps comment fields correctly from footer comments", async () => {
    const footerResults = [
      {
        id: "c2",
        body: { storage: { value: "New comment" } },
        version: { createdAt: "2024-06-15T10:00:00.000Z" },
        createdBy: { displayName: "Bob" },
      },
    ];

    globalThis.fetch = makeFetchWithResponses(footerResults, []);

    const client = makeClient();
    const result = await client.getNewComments("123456", "2024-03-01T00:00:00.000Z");

    expect(result).toHaveLength(1);
    expect(result[0].id).toBe("c2");
    expect(result[0].body).toBe("New comment");
    expect(result[0].author).toBe("Bob");
    expect(result[0].createdAt).toBe("2024-06-15T10:00:00.000Z");
  });

  test("maps comment fields correctly from inline comments", async () => {
    const inlineResults = [
      {
        id: "i1",
        body: { storage: { value: "Inline text" } },
        version: { createdAt: "2024-08-10T12:00:00.000Z" },
        createdBy: { displayName: "Eve" },
      },
    ];

    globalThis.fetch = makeFetchWithResponses([], inlineResults);

    const client = makeClient();
    const result = await client.getNewComments("123456", "2024-01-01T00:00:00.000Z");

    expect(result).toHaveLength(1);
    expect(result[0].id).toBe("i1");
    expect(result[0].body).toBe("Inline text");
    expect(result[0].author).toBe("Eve");
    expect(result[0].createdAt).toBe("2024-08-10T12:00:00.000Z");
  });

  test("uses publicName when displayName not present", async () => {
    const footerResults = [
      {
        id: "c1",
        body: { storage: { value: "Comment" } },
        version: { createdAt: "2025-01-01T00:00:00.000Z" },
        createdBy: { publicName: "charlie99" },
      },
    ];

    globalThis.fetch = makeFetchWithResponses(footerResults, []);

    const client = makeClient();
    const result = await client.getNewComments("123456", "2024-01-01T00:00:00.000Z");
    expect(result[0].author).toBe("charlie99");
  });
});

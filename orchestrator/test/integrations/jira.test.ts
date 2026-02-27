import { describe, test, expect, beforeEach, mock, afterEach } from "bun:test";
import { JiraRestClient } from "../../src/integrations/jira.ts";

const BASE_URL = "https://example.atlassian.net";
const EMAIL = "user@example.com";
const API_TOKEN = "test-token";
const PROJECT_KEY = "TOS";

function makeFetchMock(responses: Array<{ status: number; body: unknown }>) {
  let callIndex = 0;
  return mock(async (_url: string, _opts?: RequestInit) => {
    const response = responses[callIndex++] ?? { status: 200, body: {} };
    return {
      ok: response.status >= 200 && response.status < 300,
      status: response.status,
      statusText: response.status === 200 ? "OK" : "Error",
      json: async () => response.body,
      text: async () => JSON.stringify(response.body),
    } as Response;
  });
}

function makeClient() {
  return new JiraRestClient({ baseUrl: BASE_URL, email: EMAIL, apiToken: API_TOKEN, projectKey: PROJECT_KEY });
}

describe("JiraRestClient — createIssue", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("sends POST to /rest/api/3/issue with project key and fields", async () => {
    const returnedData = { key: "TOS-1", id: "10001" };
    let capturedUrl = "";
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (url: string, opts?: RequestInit) => {
      capturedUrl = url;
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => returnedData,
        text: async () => JSON.stringify(returnedData),
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    const result = await client.createIssue({ summary: "Build feature X" });

    expect(capturedUrl).toBe(`${BASE_URL}/rest/api/3/issue`);
    expect((capturedBody as Record<string, unknown>).fields).toMatchObject({
      project: { key: PROJECT_KEY },
      summary: "Build feature X",
    });
    expect(result).toEqual(returnedData);
  });

  test("throws on non-2xx response", async () => {
    globalThis.fetch = mock(async () => ({
      ok: false,
      status: 400,
      statusText: "Bad Request",
      json: async () => ({ errorMessages: ["Invalid fields"] }),
      text: async () => '{"errorMessages":["Invalid fields"]}',
    })) as typeof fetch;

    const client = makeClient();
    await expect(client.createIssue({ summary: "bad" })).rejects.toThrow("Jira API error 400");
  });
});

describe("JiraRestClient — createSubTask", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("sends POST with parent link and issuetype Sub-task", async () => {
    const returnedData = { key: "TOS-2", id: "10002" };
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (_url: string, opts?: RequestInit) => {
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => returnedData,
        text: async () => JSON.stringify(returnedData),
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    const result = await client.createSubTask("TOS-1", { summary: "Sub feature" });

    const body = capturedBody as { fields: Record<string, unknown> };
    expect(body.fields.parent).toEqual({ key: "TOS-1" });
    expect(body.fields.issuetype).toEqual({ name: "Sub-task" });
    expect(body.fields.summary).toBe("Sub feature");
    expect(result).toEqual(returnedData);
  });

  test("forces issuetype to Sub-task even if caller provides different type", async () => {
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (_url: string, opts?: RequestInit) => {
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({ key: "TOS-3", id: "10003" }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.createSubTask("TOS-1", {
      summary: "Sub",
      issuetype: { name: "Story" },
    });

    const body = capturedBody as { fields: Record<string, unknown> };
    expect(body.fields.issuetype).toEqual({ name: "Sub-task" });
  });
});

describe("JiraRestClient — getSubTasks", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("calls search with parent JQL and maps result to key+summary", async () => {
    let capturedUrl = "";

    globalThis.fetch = mock(async (url: string) => {
      capturedUrl = url;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({
          issues: [
            { key: "TOS-2", fields: { summary: "Sub A" } },
            { key: "TOS-3", fields: { summary: "Sub B" } },
          ],
        }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    const result = await client.getSubTasks("TOS-1");

    expect(capturedUrl).toContain("/rest/api/3/search");
    expect(capturedUrl).toContain("parent%3DTOS-1");
    expect(result).toHaveLength(2);
    expect(result[0]).toEqual({ key: "TOS-2", summary: "Sub A" });
    expect(result[1]).toEqual({ key: "TOS-3", summary: "Sub B" });
  });

  test("returns empty array when no subtasks exist", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({ issues: [] }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.getSubTasks("TOS-1");
    expect(result).toHaveLength(0);
  });
});

describe("JiraRestClient — transition", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("fetches transitions, finds by name, and POSTs transition", async () => {
    const calls: Array<{ url: string; method: string; body?: unknown }> = [];

    globalThis.fetch = mock(async (url: string, opts?: RequestInit) => {
      const method = opts?.method ?? "GET";
      const body = opts?.body ? JSON.parse(opts.body as string) : undefined;
      calls.push({ url, method, body });

      if (url.endsWith("/transitions") && method === "GET") {
        return {
          ok: true,
          status: 200,
          statusText: "OK",
          json: async () => ({
            transitions: [
              { id: "10", name: "To Do" },
              { id: "21", name: "In Progress" },
              { id: "31", name: "Done" },
            ],
          }),
          text: async () => "{}",
        } as Response;
      }

      // POST transitions
      return {
        ok: true,
        status: 204,
        statusText: "No Content",
        json: async () => undefined,
        text: async () => "",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.transition("TOS-5", "Done");

    expect(calls).toHaveLength(2);
    expect(calls[0].url).toContain("/issue/TOS-5/transitions");
    expect(calls[0].method).toBe("GET");
    expect(calls[1].url).toContain("/issue/TOS-5/transitions");
    expect(calls[1].method).toBe("POST");
    expect((calls[1].body as { transition: { id: string } }).transition.id).toBe("31");
  });

  test("throws when transition name not found", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({
        transitions: [{ id: "10", name: "To Do" }],
      }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    await expect(client.transition("TOS-5", "Nonexistent")).rejects.toThrow(
      'Jira transition "Nonexistent" not found'
    );
  });
});

describe("JiraRestClient — addComment", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("POSTs comment with ADF body format to /issue/{key}/comment", async () => {
    let capturedUrl = "";
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (url: string, opts?: RequestInit) => {
      capturedUrl = url;
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 201,
        statusText: "Created",
        json: async () => ({ id: "10100" }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.addComment("TOS-5", "This is a test comment");

    expect(capturedUrl).toContain("/issue/TOS-5/comment");
    const body = capturedBody as { body: { type: string; content: Array<{ content: Array<{ text: string }> }> } };
    expect(body.body.type).toBe("doc");
    expect(body.body.content[0].content[0].text).toBe("This is a test comment");
  });

  test("throws on API error", async () => {
    globalThis.fetch = mock(async () => ({
      ok: false,
      status: 403,
      statusText: "Forbidden",
      json: async () => ({ errorMessages: ["Not authorized"] }),
      text: async () => "Not authorized",
    })) as typeof fetch;

    const client = makeClient();
    await expect(client.addComment("TOS-5", "test")).rejects.toThrow("Jira API error 403");
  });
});

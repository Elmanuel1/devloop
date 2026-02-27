import { describe, test, expect, beforeEach, mock, afterEach } from "bun:test";
import { SlackWebhookClient } from "../../src/integrations/slack.ts";

const WEBHOOK_URL = "https://hooks.slack.com/services/T123/B456/test";
const BOT_TOKEN = "xoxb-test-bot-token";

function makeClient() {
  return new SlackWebhookClient({ webhookUrl: WEBHOOK_URL, botToken: BOT_TOKEN });
}

describe("SlackWebhookClient — send", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("POSTs message to webhook URL with text field", async () => {
    let capturedUrl = "";
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (url: string, opts?: RequestInit) => {
      capturedUrl = url;
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({ ok: true }),
        text: async () => "ok",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.send("Hello from orchestrator");

    expect(capturedUrl).toBe(WEBHOOK_URL);
    const body = capturedBody as Record<string, unknown>;
    expect(body.text).toBe("Hello from orchestrator");
    expect(body.thread_ts).toBeUndefined();
  });

  test("includes thread_ts when provided", async () => {
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (_url: string, opts?: RequestInit) => {
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({ ok: true }),
        text: async () => "ok",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.send("Threaded reply", "1692345678.123456");

    const body = capturedBody as Record<string, unknown>;
    expect(body.thread_ts).toBe("1692345678.123456");
  });

  test("throws on non-2xx response", async () => {
    globalThis.fetch = mock(async () => ({
      ok: false,
      status: 500,
      statusText: "Internal Server Error",
      json: async () => ({}),
      text: async () => "Internal error",
    })) as typeof fetch;

    const client = makeClient();
    await expect(client.send("fail")).rejects.toThrow("Slack webhook error 500");
  });
});

describe("SlackWebhookClient — postMessage", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("POSTs to Slack chat.postMessage with Bearer auth", async () => {
    let capturedUrl = "";
    let capturedHeaders: Record<string, string> = {};
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (url: string, opts?: RequestInit) => {
      capturedUrl = url;
      capturedHeaders = (opts?.headers ?? {}) as Record<string, string>;
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({ ok: true, ts: "1692345678.000000" }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.postMessage("#general", "Hello world");

    expect(capturedUrl).toBe("https://slack.com/api/chat.postMessage");
    expect(capturedHeaders["Authorization"]).toBe(`Bearer ${BOT_TOKEN}`);
    const body = capturedBody as Record<string, unknown>;
    expect(body.channel).toBe("#general");
    expect(body.text).toBe("Hello world");
    expect(body.thread_ts).toBeUndefined();
  });

  test("includes thread_ts when provided", async () => {
    let capturedBody: unknown = null;

    globalThis.fetch = mock(async (_url: string, opts?: RequestInit) => {
      capturedBody = opts?.body ? JSON.parse(opts.body as string) : null;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({ ok: true }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    await client.postMessage("#dev", "Threaded", "1692000000.111111");

    const body = capturedBody as Record<string, unknown>;
    expect(body.thread_ts).toBe("1692000000.111111");
  });

  test("throws when Slack API returns ok: false", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({ ok: false, error: "channel_not_found" }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    await expect(client.postMessage("#nonexistent", "hello")).rejects.toThrow(
      "channel_not_found"
    );
  });

  test("throws on HTTP error", async () => {
    globalThis.fetch = mock(async () => ({
      ok: false,
      status: 429,
      statusText: "Too Many Requests",
      json: async () => ({}),
      text: async () => "rate limited",
    })) as typeof fetch;

    const client = makeClient();
    await expect(client.postMessage("#ch", "msg")).rejects.toThrow("Slack API error 429");
  });
});

describe("SlackWebhookClient — getUserName", () => {
  let originalFetch: typeof fetch;

  beforeEach(() => {
    originalFetch = globalThis.fetch;
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  test("GETs users.info with Bearer auth and returns display_name", async () => {
    let capturedUrl = "";
    let capturedHeaders: Record<string, string> = {};

    globalThis.fetch = mock(async (url: string, opts?: RequestInit) => {
      capturedUrl = url;
      capturedHeaders = (opts?.headers ?? {}) as Record<string, string>;
      return {
        ok: true,
        status: 200,
        statusText: "OK",
        json: async () => ({
          ok: true,
          user: {
            name: "alice123",
            real_name: "Alice Smith",
            profile: { display_name: "Alice", real_name: "Alice Smith" },
          },
        }),
        text: async () => "{}",
      } as Response;
    }) as typeof fetch;

    const client = makeClient();
    const result = await client.getUserName("U123ABC");

    expect(capturedUrl).toContain("users.info");
    expect(capturedUrl).toContain("U123ABC");
    expect(capturedHeaders["Authorization"]).toBe(`Bearer ${BOT_TOKEN}`);
    expect(result).toBe("Alice");
  });

  test("falls back to real_name when display_name is empty", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({
        ok: true,
        user: {
          name: "bob123",
          real_name: "Bob Jones",
          profile: { display_name: "", real_name: "Bob Jones" },
        },
      }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.getUserName("U456DEF");
    expect(result).toBe("Bob Jones");
  });

  test("falls back to user.name when profile names are empty", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({
        ok: true,
        user: {
          name: "charlie99",
          real_name: "",
          profile: { display_name: "", real_name: "" },
        },
      }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.getUserName("U789GHI");
    expect(result).toBe("charlie99");
  });

  test("falls back to userId when no name fields present", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({
        ok: true,
        user: {},
      }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    const result = await client.getUserName("UXYZ999");
    expect(result).toBe("UXYZ999");
  });

  test("throws when API returns ok: false", async () => {
    globalThis.fetch = mock(async () => ({
      ok: true,
      status: 200,
      statusText: "OK",
      json: async () => ({ ok: false, error: "user_not_found" }),
      text: async () => "{}",
    })) as typeof fetch;

    const client = makeClient();
    await expect(client.getUserName("UBAD")).rejects.toThrow("user_not_found");
  });

  test("throws on HTTP error", async () => {
    globalThis.fetch = mock(async () => ({
      ok: false,
      status: 401,
      statusText: "Unauthorized",
      json: async () => ({}),
      text: async () => "Unauthorized",
    })) as typeof fetch;

    const client = makeClient();
    await expect(client.getUserName("U123")).rejects.toThrow("Slack API error 401");
  });
});

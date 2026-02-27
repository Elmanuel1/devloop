import { describe, test, expect } from "bun:test";
import { SlackEventParser } from "../../../src/webhooks/parsers/slack.ts";
import type { TaskRequestEvent } from "../../../src/types.ts";

function makeContext(body: Record<string, unknown>): import("hono").Context {
  return {
    req: {
      header(_name: string): string | undefined {
        return undefined;
      },
      async json(): Promise<Record<string, unknown>> {
        return body;
      },
    },
  } as unknown as import("hono").Context;
}

const parser = new SlackEventParser();

describe("SlackEventParser — url_verification", () => {
  test("url_verification challenge returns empty array", async () => {
    const body = {
      type: "url_verification",
      challenge: "abc123challenge",
    };
    const events = await parser.parse(makeContext(body));
    expect(events).toHaveLength(0);
  });
});

describe("SlackEventParser — message events", () => {
  test("regular user message → TaskRequestEvent", async () => {
    const body = {
      type: "event_callback",
      event: {
        type: "message",
        text: "Build me a payments feature",
        user: "U12345",
        channel: "C67890",
        ts: "1234567890.000001",
      },
    };
    const events = await parser.parse(makeContext(body));

    expect(events).toHaveLength(1);
    const event = events[0] as TaskRequestEvent;
    expect(event.type).toBe("task:requested");
    expect(event.source).toBe("slack");
    expect(event.message).toBe("Build me a payments feature");
    expect(event.senderId).toBe("U12345");
    expect(typeof event.id).toBe("string");
    expect(event.id.length).toBeGreaterThan(0);
  });

  test("TaskRequestEvent has a no-op ack function", async () => {
    const body = {
      type: "event_callback",
      event: {
        type: "message",
        text: "Hello",
        user: "U99999",
      },
    };
    const events = await parser.parse(makeContext(body));
    const event = events[0] as TaskRequestEvent;

    // ack should be callable without throwing
    await expect(event.ack("got it")).resolves.toBeUndefined();
  });

  test("bot message (bot_id present) → filtered, returns empty", async () => {
    const body = {
      type: "event_callback",
      event: {
        type: "message",
        text: "I am a bot",
        bot_id: "B00001",
        user: "U00001",
      },
    };
    const events = await parser.parse(makeContext(body));
    expect(events).toHaveLength(0);
  });

  test("bot message subtype → filtered, returns empty", async () => {
    const body = {
      type: "event_callback",
      event: {
        type: "message",
        text: "Bot says hi",
        subtype: "bot_message",
        user: "U00002",
      },
    };
    const events = await parser.parse(makeContext(body));
    expect(events).toHaveLength(0);
  });
});

describe("SlackEventParser — non-message events", () => {
  test("non-message event type within event_callback → empty array", async () => {
    const body = {
      type: "event_callback",
      event: {
        type: "reaction_added",
        user: "U12345",
      },
    };
    const events = await parser.parse(makeContext(body));
    expect(events).toHaveLength(0);
  });

  test("event_callback without event field → empty array", async () => {
    const body = {
      type: "event_callback",
    };
    const events = await parser.parse(makeContext(body));
    expect(events).toHaveLength(0);
  });

  test("completely unknown payload type → empty array", async () => {
    const body = {
      type: "block_actions",
      payload: "something",
    };
    const events = await parser.parse(makeContext(body));
    expect(events).toHaveLength(0);
  });
});

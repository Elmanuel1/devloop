import { describe, test, expect } from "bun:test";
import { SlackVerifier } from "../../../src/webhooks/verify/slack.ts";

const TEST_SECRET = "test-slack-signing-secret";

async function computeSlackSignature(secret: string, timestamp: string, body: string): Promise<string> {
  const encoder = new TextEncoder();
  const baseString = `v0:${timestamp}:${body}`;
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signatureBytes = await crypto.subtle.sign("HMAC", key, encoder.encode(baseString));
  const hex = Array.from(new Uint8Array(signatureBytes))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return `v0=${hex}`;
}

function makeContext(body: string, timestamp: string | null, signature: string | null): import("hono").Context {
  const headers: Record<string, string> = {};
  if (timestamp !== null) headers["X-Slack-Request-Timestamp"] = timestamp;
  if (signature !== null) headers["X-Slack-Signature"] = signature;

  return {
    req: {
      header(name: string): string | undefined {
        return headers[name];
      },
      async text(): Promise<string> {
        return body;
      },
    },
  } as unknown as import("hono").Context;
}

function nowTimestamp(): string {
  return String(Math.floor(Date.now() / 1000));
}

function oldTimestamp(): string {
  // 6 minutes ago — beyond the 5-minute replay window
  return String(Math.floor(Date.now() / 1000) - 360);
}

describe("SlackVerifier", () => {
  test("verify passes for a valid signature with current timestamp", async () => {
    const body = "payload=test";
    const ts = nowTimestamp();
    const signature = await computeSlackSignature(TEST_SECRET, ts, body);
    const verifier = new SlackVerifier(TEST_SECRET);
    const c = makeContext(body, ts, signature);

    await expect(verifier.verify(c)).resolves.toBeUndefined();
  });

  test("verify throws for an invalid signature", async () => {
    const body = "payload=test";
    const ts = nowTimestamp();
    const badSig = "v0=0000000000000000000000000000000000000000000000000000000000000000";
    const verifier = new SlackVerifier(TEST_SECRET);
    const c = makeContext(body, ts, badSig);

    await expect(verifier.verify(c)).rejects.toThrow("Invalid Slack webhook signature");
  });

  test("verify throws when timestamp is too old (replay attack)", async () => {
    const body = "payload=test";
    const ts = oldTimestamp();
    const signature = await computeSlackSignature(TEST_SECRET, ts, body);
    const verifier = new SlackVerifier(TEST_SECRET);
    const c = makeContext(body, ts, signature);

    await expect(verifier.verify(c)).rejects.toThrow("replay attack protection");
  });

  test("verify throws when X-Slack-Request-Timestamp header is missing", async () => {
    const body = "payload=test";
    const signature = "v0=anything";
    const verifier = new SlackVerifier(TEST_SECRET);
    const c = makeContext(body, null, signature);

    await expect(verifier.verify(c)).rejects.toThrow("Missing X-Slack-Request-Timestamp header");
  });

  test("verify throws when X-Slack-Signature header is missing", async () => {
    const body = "payload=test";
    const ts = nowTimestamp();
    const verifier = new SlackVerifier(TEST_SECRET);
    const c = makeContext(body, ts, null);

    await expect(verifier.verify(c)).rejects.toThrow("Missing X-Slack-Signature header");
  });

  test("verify throws when body has been tampered with", async () => {
    const originalBody = "payload=original";
    const tamperedBody = "payload=tampered";
    const ts = nowTimestamp();
    const signature = await computeSlackSignature(TEST_SECRET, ts, originalBody);
    const verifier = new SlackVerifier(TEST_SECRET);
    const c = makeContext(tamperedBody, ts, signature);

    await expect(verifier.verify(c)).rejects.toThrow("Invalid Slack webhook signature");
  });

  test("verify throws when signing secret is not configured", async () => {
    const body = "payload=test";
    const ts = nowTimestamp();
    const signature = "v0=anything";
    const verifier = new SlackVerifier(""); // empty secret = not configured
    const c = makeContext(body, ts, signature);

    await expect(verifier.verify(c)).rejects.toThrow("Slack signing secret not configured");
  });
});

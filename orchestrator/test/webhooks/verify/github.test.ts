import { describe, test, expect } from "bun:test";
import { GitHubVerifier } from "../../../src/webhooks/verify/github.ts";

const TEST_SECRET = "test-github-webhook-secret";

async function computeSignature(secret: string, body: string): Promise<string> {
  const encoder = new TextEncoder();
  const key = await crypto.subtle.importKey(
    "raw",
    encoder.encode(secret),
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const signatureBytes = await crypto.subtle.sign("HMAC", key, encoder.encode(body));
  const hex = Array.from(new Uint8Array(signatureBytes))
    .map((b) => b.toString(16).padStart(2, "0"))
    .join("");
  return `sha256=${hex}`;
}

function makeContext(body: string, signature: string | null): import("hono").Context {
  const headers: Record<string, string> = {};
  if (signature !== null) {
    headers["X-Hub-Signature-256"] = signature;
  }

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

describe("GitHubVerifier", () => {
  test("verify passes for a valid signature", async () => {
    const body = JSON.stringify({ action: "opened" });
    const signature = await computeSignature(TEST_SECRET, body);
    const verifier = new GitHubVerifier(TEST_SECRET);
    const c = makeContext(body, signature);

    await expect(verifier.verify(c)).resolves.toBeUndefined();
  });

  test("verify throws for an invalid signature", async () => {
    const body = JSON.stringify({ action: "opened" });
    const badSignature = "sha256=0000000000000000000000000000000000000000000000000000000000000000";
    const verifier = new GitHubVerifier(TEST_SECRET);
    const c = makeContext(body, badSignature);

    await expect(verifier.verify(c)).rejects.toThrow("Invalid GitHub webhook signature");
  });

  test("verify throws when X-Hub-Signature-256 header is missing", async () => {
    const body = JSON.stringify({ action: "opened" });
    const verifier = new GitHubVerifier(TEST_SECRET);
    const c = makeContext(body, null);

    await expect(verifier.verify(c)).rejects.toThrow("Missing X-Hub-Signature-256 header");
  });

  test("verify throws when signature prefix is wrong", async () => {
    const body = JSON.stringify({ action: "opened" });
    const correctHex = (await computeSignature(TEST_SECRET, body)).slice("sha256=".length);
    const wrongPrefixSig = `sha1=${correctHex}`;
    const verifier = new GitHubVerifier(TEST_SECRET);
    const c = makeContext(body, wrongPrefixSig);

    await expect(verifier.verify(c)).rejects.toThrow("Invalid GitHub webhook signature");
  });

  test("verify throws when body has been tampered with", async () => {
    const originalBody = JSON.stringify({ action: "opened" });
    const tamperedBody = JSON.stringify({ action: "closed" });
    const signature = await computeSignature(TEST_SECRET, originalBody);
    const verifier = new GitHubVerifier(TEST_SECRET);
    const c = makeContext(tamperedBody, signature);

    await expect(verifier.verify(c)).rejects.toThrow("Invalid GitHub webhook signature");
  });

  test("verify throws when secret is not configured", async () => {
    const body = JSON.stringify({ action: "opened" });
    const signature = "sha256=anything";
    const verifier = new GitHubVerifier(""); // empty secret = not configured
    const c = makeContext(body, signature);

    await expect(verifier.verify(c)).rejects.toThrow("GitHub webhook secret not configured");
  });
});

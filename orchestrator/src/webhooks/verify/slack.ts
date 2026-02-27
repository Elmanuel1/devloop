import type { Context } from "hono";
import type { WebhookVerifier } from "../../types.ts";
import { config } from "../../config.ts";

const REPLAY_WINDOW_MS = 5 * 60 * 1000; // 5 minutes

export class SlackVerifier implements WebhookVerifier {
  private readonly secret: string;

  constructor(secret?: string) {
    this.secret = secret ?? config.slack.signingSecret;
  }

  async verify(c: Context): Promise<void> {
    const timestamp = c.req.header("X-Slack-Request-Timestamp");
    const signature = c.req.header("X-Slack-Signature");

    if (!timestamp) {
      throw new Error("Missing X-Slack-Request-Timestamp header");
    }

    if (!signature) {
      throw new Error("Missing X-Slack-Signature header");
    }

    const requestTime = parseInt(timestamp, 10) * 1000;
    const now = Date.now();

    if (Math.abs(now - requestTime) > REPLAY_WINDOW_MS) {
      throw new Error("Slack request timestamp too old — replay attack protection");
    }

    if (!this.secret) {
      throw new Error("Slack signing secret not configured");
    }

    const body = await c.req.text();
    const baseString = `v0:${timestamp}:${body}`;
    const encoder = new TextEncoder();

    const key = await crypto.subtle.importKey(
      "raw",
      encoder.encode(this.secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"]
    );

    const signatureBytes = await crypto.subtle.sign("HMAC", key, encoder.encode(baseString));
    const hex = Array.from(new Uint8Array(signatureBytes))
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    const expected = `v0=${hex}`;

    if (!timingSafeEqual(expected, signature)) {
      throw new Error("Invalid Slack webhook signature");
    }
  }
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) {
    return false;
  }

  const encoderA = new TextEncoder().encode(a);
  const encoderB = new TextEncoder().encode(b);

  let result = 0;
  for (let i = 0; i < encoderA.length; i++) {
    result |= encoderA[i] ^ encoderB[i];
  }

  return result === 0;
}

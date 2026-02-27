import type { Context } from "hono";
import type { WebhookVerifier } from "../../types.ts";
import { config } from "../../config.ts";

export class GitHubVerifier implements WebhookVerifier {
  private readonly secret: string;

  constructor(secret?: string) {
    this.secret = secret ?? config.github.webhookSecret;
  }

  async verify(c: Context): Promise<void> {
    const signature = c.req.header("X-Hub-Signature-256");

    if (!signature) {
      throw new Error("Missing X-Hub-Signature-256 header");
    }

    if (!this.secret) {
      throw new Error("GitHub webhook secret not configured");
    }

    const body = await c.req.text();
    const encoder = new TextEncoder();

    const key = await crypto.subtle.importKey(
      "raw",
      encoder.encode(this.secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"]
    );

    const signatureBytes = await crypto.subtle.sign("HMAC", key, encoder.encode(body));
    const hex = Array.from(new Uint8Array(signatureBytes))
      .map((b) => b.toString(16).padStart(2, "0"))
      .join("");
    const expected = `sha256=${hex}`;

    if (!timingSafeEqual(expected, signature)) {
      throw new Error("Invalid GitHub webhook signature");
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

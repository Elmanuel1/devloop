import type { SlackClient } from "../types.ts";

export interface SlackConfig {
  webhookUrl: string;
  botToken: string;
}

interface SlackApiResponse {
  ok: boolean;
  error?: string;
  ts?: string;
}

interface SlackUserInfoResponse {
  ok: boolean;
  user?: {
    name?: string;
    real_name?: string;
    profile?: {
      display_name?: string;
      real_name?: string;
    };
  };
  error?: string;
}

export class SlackWebhookClient implements SlackClient {
  private readonly webhookUrl: string;
  private readonly botToken: string;

  constructor(cfg: SlackConfig) {
    this.webhookUrl = cfg.webhookUrl;
    this.botToken = cfg.botToken;
  }

  async send(message: string, threadTs?: string): Promise<void> {
    const body: Record<string, unknown> = { text: message };
    if (threadTs) {
      body.thread_ts = threadTs;
    }

    const res = await fetch(this.webhookUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`Slack webhook error ${res.status} ${res.statusText}: ${text}`);
    }
  }

  async postMessage(channel: string, text: string, threadTs?: string): Promise<void> {
    const body: Record<string, unknown> = { channel, text };
    if (threadTs) {
      body.thread_ts = threadTs;
    }

    const res = await fetch("https://slack.com/api/chat.postMessage", {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.botToken}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    });

    if (!res.ok) {
      const responseText = await res.text().catch(() => "");
      throw new Error(`Slack API error ${res.status} ${res.statusText}: ${responseText}`);
    }

    const data = (await res.json()) as SlackApiResponse;
    if (!data.ok) {
      throw new Error(`Slack API error: ${data.error ?? "unknown error"}`);
    }
  }

  async getUserName(userId: string): Promise<string> {
    const res = await fetch(`https://slack.com/api/users.info?user=${encodeURIComponent(userId)}`, {
      method: "GET",
      headers: {
        Authorization: `Bearer ${this.botToken}`,
        "Content-Type": "application/json",
      },
    });

    if (!res.ok) {
      const text = await res.text().catch(() => "");
      throw new Error(`Slack API error ${res.status} ${res.statusText}: ${text}`);
    }

    const data = (await res.json()) as SlackUserInfoResponse;
    if (!data.ok) {
      throw new Error(`Slack users.info error: ${data.error ?? "unknown error"}`);
    }

    return (
      data.user?.profile?.display_name ||
      data.user?.profile?.real_name ||
      data.user?.real_name ||
      data.user?.name ||
      userId
    );
  }
}

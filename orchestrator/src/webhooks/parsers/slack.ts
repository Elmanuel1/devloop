import type { Context } from "hono";
import type { EventParser, TaskRequestEvent } from "../../types.ts";

interface SlackUrlVerificationPayload {
  type: "url_verification";
  challenge: string;
}

interface SlackEventPayload {
  type: "event_callback";
  event?: {
    type: string;
    text?: string;
    user?: string;
    bot_id?: string;
    subtype?: string;
    channel?: string;
    ts?: string;
  };
}

type SlackPayload = SlackUrlVerificationPayload | SlackEventPayload | Record<string, unknown>;

export class SlackEventParser implements EventParser<TaskRequestEvent> {
  async parse(c: Context): Promise<TaskRequestEvent[]> {
    const body = await c.req.json() as SlackPayload;

    if (isUrlVerification(body)) {
      // Respond to Slack's challenge — the verifier/route handler should return the challenge.
      // The parser signals this with a special no-op event array; actual response is handled at route level.
      // We return empty here — route handler must detect and reply with challenge separately.
      return [];
    }

    if (!isEventCallback(body)) {
      return [];
    }

    const event = body.event;
    if (!event) return [];

    if (event.type !== "message") return [];

    // Filter bot messages
    if (event.bot_id) return [];
    if (event.subtype === "bot_message") return [];

    const text = event.text ?? "";
    const userId = event.user ?? "";

    const taskEvent: TaskRequestEvent = {
      id: crypto.randomUUID(),
      source: "slack",
      type: "task:requested",
      raw: body,
      message: text,
      senderId: userId,
      senderName: userId, // Caller can resolve the name via SlackClient.getUserName if needed
      ack: async (_reply: string): Promise<void> => {
        // No-op by default — actual ack is sent at the handler level via SlackClient
      },
    };

    return [taskEvent];
  }
}

function isUrlVerification(payload: SlackPayload): payload is SlackUrlVerificationPayload {
  return (payload as SlackUrlVerificationPayload).type === "url_verification";
}

function isEventCallback(payload: SlackPayload): payload is SlackEventPayload {
  return (payload as SlackEventPayload).type === "event_callback";
}

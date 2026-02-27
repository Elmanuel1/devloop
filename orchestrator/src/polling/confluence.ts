import type { ConfluenceClient, OrchestratorEvent } from "../types.ts";
import { config } from "../config.ts";
import { parseConfluenceApproval, parseConfluenceComment } from "../webhooks/parsers/confluence.ts";
import log from "../utils/logger.ts";

export function startConfluencePolling(
  confluence: ConfluenceClient,
  dispatch: (event: OrchestratorEvent) => void,
  intervalMs?: number
): { stop: () => void } {
  const pollInterval = intervalMs ?? config.confluencePollIntervalMs;
  let lastPollTime = new Date(0).toISOString();

  async function poll(): Promise<void> {
    const pollStart = new Date().toISOString();

    try {
      const pages = await confluence.getPagesInReview();

      for (const page of pages) {
        const designId = extractDesignId(page.title);
        if (!designId) continue;

        const pageRef = { id: page.id, designId };

        const state = await confluence.getContentState(page.id);

        if (state === "approved") {
          const event = parseConfluenceApproval(pageRef);
          dispatch(event);
        }

        const comments = await confluence.getNewComments(page.id, lastPollTime);

        for (const comment of comments) {
          const event = parseConfluenceComment(pageRef, {
            id: comment.id,
            body: comment.body,
            author: comment.author,
          });
          dispatch(event);
        }
      }
    } catch (err) {
      log.error("Confluence polling error", {
        error: err instanceof Error ? err.message : String(err),
      });
    }

    lastPollTime = pollStart;
  }

  const timer = setInterval(() => {
    poll();
  }, pollInterval);

  return {
    stop(): void {
      clearInterval(timer);
    },
  };
}

function extractDesignId(pageTitle: string): string | null {
  // Page titles are expected to contain the designId in brackets e.g. "[design-uuid] Feature Name"
  // or just be the designId itself. We try a loose extraction.
  const match = pageTitle.match(/\[([^\]]+)\]/);
  if (match) return match[1];

  // If the title IS a UUID-like string, use it directly
  if (/^[0-9a-f-]{32,36}$/i.test(pageTitle.trim())) {
    return pageTitle.trim();
  }

  return null;
}

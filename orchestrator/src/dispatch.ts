import type { OrchestratorEvent, TaskHandler, TaskQueue } from "./types.ts";
import log from "./utils/logger.ts";

export function createDispatch(
  handlers: TaskHandler[],
  queues: Record<string, TaskQueue>
): (event: OrchestratorEvent) => void {
  return function dispatch(event: OrchestratorEvent): void {
    const handler = handlers.find((h) => h.matches(event));

    if (!handler) {
      log.warn("Unhandled event — no matching handler", { type: event.type, id: event.id });
      return;
    }

    queues[handler.queue].push(event);
  };
}

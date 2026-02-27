import { describe, test, expect, mock, beforeEach } from "bun:test";
import { createDispatch } from "../src/dispatch.ts";
import type { OrchestratorEvent, TaskHandler, TaskQueue } from "../src/types.ts";

function makeEvent(type: string): OrchestratorEvent {
  return {
    id: crypto.randomUUID(),
    source: "test",
    type,
    raw: {},
  };
}

function makeHandler(matchType: string, queue: string): TaskHandler & { calls: OrchestratorEvent[] } {
  const calls: OrchestratorEvent[] = [];
  return {
    queue,
    calls,
    matches(event: OrchestratorEvent): boolean {
      return event.type === matchType;
    },
    async handle(event: OrchestratorEvent): Promise<void> {
      calls.push(event);
    },
  };
}

function makeQueue(): TaskQueue & { pushed: OrchestratorEvent[] } {
  const pushed: OrchestratorEvent[] = [];
  return {
    pushed,
    push(event: OrchestratorEvent): void {
      pushed.push(event);
    },
    on(_event: "drain", _cb: () => void): void {
      // no-op for test
    },
    destroy(): void {
      // no-op for test
    },
  };
}

describe("createDispatch", () => {
  test("dispatches event to queue of first matching handler", () => {
    const handler = makeHandler("task:requested", "architect");
    const queue = makeQueue();
    const queues: Record<string, TaskQueue> = { architect: queue };

    const dispatch = createDispatch([handler], queues);
    const event = makeEvent("task:requested");
    dispatch(event);

    expect(queue.pushed).toHaveLength(1);
    expect(queue.pushed[0]).toBe(event);
  });

  test("first match wins — second handler with same match type does not receive event", () => {
    const first = makeHandler("ci:failed", "codeWriter");
    const second = makeHandler("ci:failed", "reviewer");
    const codeWriterQueue = makeQueue();
    const reviewerQueue = makeQueue();
    const queues: Record<string, TaskQueue> = {
      codeWriter: codeWriterQueue,
      reviewer: reviewerQueue,
    };

    const dispatch = createDispatch([first, second], queues);
    dispatch(makeEvent("ci:failed"));

    expect(codeWriterQueue.pushed).toHaveLength(1);
    expect(reviewerQueue.pushed).toHaveLength(0);
  });

  test("logs warning when no handler matches", () => {
    const stderrWrites: string[] = [];
    const origWrite = process.stderr.write.bind(process.stderr);
    process.stderr.write = (chunk: Uint8Array | string): boolean => {
      stderrWrites.push(typeof chunk === "string" ? chunk : chunk.toString());
      return true;
    };

    const dispatch = createDispatch([], {});
    dispatch(makeEvent("unknown:event"));

    process.stderr.write = origWrite;

    const combined = stderrWrites.join("");
    expect(combined).toContain("Unhandled event");
    expect(combined).toContain("unknown:event");
  });

  test("does not push to queue when no handler matches", () => {
    const queue = makeQueue();
    const dispatch = createDispatch([], { architect: queue });
    dispatch(makeEvent("unknown:event"));

    expect(queue.pushed).toHaveLength(0);
  });

  test("dispatches to correct queue name declared by handler", () => {
    const architectHandler = makeHandler("task:requested", "architect");
    const reviewerHandler = makeHandler("ci:passed", "reviewer");
    const architectQueue = makeQueue();
    const reviewerQueue = makeQueue();

    const queues: Record<string, TaskQueue> = {
      architect: architectQueue,
      reviewer: reviewerQueue,
    };

    const dispatch = createDispatch([architectHandler, reviewerHandler], queues);

    dispatch(makeEvent("task:requested"));
    dispatch(makeEvent("ci:passed"));

    expect(architectQueue.pushed).toHaveLength(1);
    expect(reviewerQueue.pushed).toHaveLength(1);
    expect(architectQueue.pushed[0].type).toBe("task:requested");
    expect(reviewerQueue.pushed[0].type).toBe("ci:passed");
  });

  test("handles multiple events sequentially", () => {
    const handler = makeHandler("task:requested", "architect");
    const queue = makeQueue();
    const dispatch = createDispatch([handler], { architect: queue });

    dispatch(makeEvent("task:requested"));
    dispatch(makeEvent("task:requested"));
    dispatch(makeEvent("task:requested"));

    expect(queue.pushed).toHaveLength(3);
  });
});

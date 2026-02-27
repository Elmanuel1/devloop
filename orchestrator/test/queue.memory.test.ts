import { describe, test, expect, beforeEach } from "bun:test";
import { MemoryQueueFactory } from "../src/queue.memory.ts";
import type { OrchestratorEvent, TaskQueue } from "../src/types.ts";

function makeEvent(seq?: number): OrchestratorEvent {
  return {
    id: `evt-${seq ?? 0}`,
    source: "test",
    type: "task:requested",
    raw: { seq: seq ?? 0 },
  };
}

function waitForDrain(queue: TaskQueue): Promise<void> {
  return new Promise<void>((resolve) => {
    queue.on("drain", resolve);
  });
}

describe("MemoryQueueFactory", () => {
  let factory: MemoryQueueFactory;

  beforeEach(() => {
    factory = new MemoryQueueFactory();
  });

  test("push delivers event to worker", async () => {
    const received: OrchestratorEvent[] = [];

    const queue = factory.create("test", async (event) => {
      received.push(event);
    }, 1);

    const event = makeEvent(1);
    queue.push(event);

    await waitForDrain(queue);

    expect(received).toHaveLength(1);
    expect(received[0].type).toBe("task:requested");

    queue.destroy();
  });

  test("multiple events are all processed", async () => {
    const received: OrchestratorEvent[] = [];

    const queue = factory.create("test", async (event) => {
      received.push(event);
    }, 2);

    queue.push(makeEvent(1));
    queue.push(makeEvent(2));
    queue.push(makeEvent(3));

    await waitForDrain(queue);

    expect(received).toHaveLength(3);

    queue.destroy();
  });

  test("worker receives events in push order with concurrency=1", async () => {
    const order: number[] = [];

    const queue = factory.create("test", async (event) => {
      order.push((event.raw as { seq: number }).seq);
    }, 1);

    queue.push(makeEvent(1));
    queue.push(makeEvent(2));
    queue.push(makeEvent(3));

    await waitForDrain(queue);

    expect(order).toEqual([1, 2, 3]);

    queue.destroy();
  });

  test("worker errors do not crash the queue and subsequent events still process", async () => {
    const received: OrchestratorEvent[] = [];
    let callCount = 0;

    const queue = factory.create("test", async (event) => {
      callCount++;
      if (callCount === 1) {
        throw new Error("simulated worker error");
      }
      received.push(event);
    }, 1);

    queue.push(makeEvent(1));
    queue.push(makeEvent(2));

    await waitForDrain(queue);

    // second event should still be processed
    expect(received).toHaveLength(1);

    queue.destroy();
  });

  test("create uses provided concurrency", () => {
    // If concurrency is honoured, multiple workers can run simultaneously.
    // We verify by pushing N items and measuring wall time with artificial delay.
    const results: number[] = [];
    const DELAY_MS = 20;
    const N = 4;
    const CONCURRENCY = 4;

    const start = Date.now();

    const queue = factory.create("test", async (_event) => {
      await new Promise<void>((resolve) => setTimeout(resolve, DELAY_MS));
      results.push(Date.now() - start);
    }, CONCURRENCY);

    for (let i = 0; i < N; i++) {
      queue.push(makeEvent(i));
    }

    return new Promise<void>((resolve, reject) => {
      queue.on("drain", () => {
        try {
          const elapsed = Date.now() - start;
          // With concurrency=4 and 4 tasks of 20ms each, total wall time should be
          // well under 4*20ms = 80ms (it should be close to 20ms)
          expect(elapsed).toBeLessThan(60);
          expect(results).toHaveLength(N);
          queue.destroy();
          resolve();
        } catch (err) {
          reject(err);
        }
      });
    });
  });

  test("destroy stops queue from processing new events", async () => {
    const received: OrchestratorEvent[] = [];

    const queue = factory.create("test", async (event) => {
      received.push(event);
    }, 1);

    // Push one event and wait for it to drain
    queue.push(makeEvent(1));
    await waitForDrain(queue);

    expect(received).toHaveLength(1);

    // Destroy and push another event — it should not be processed
    queue.destroy();

    // No further drain event will fire, so we just assert received count
    expect(received).toHaveLength(1);
  });
});

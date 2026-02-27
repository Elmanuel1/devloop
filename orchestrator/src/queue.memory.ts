import BetterQueue from "better-queue";
import { config } from "./config.ts";
import type { OrchestratorEvent, TaskQueue, TaskQueueFactory, QueueWorker } from "./types.ts";

class MemoryQueue implements TaskQueue {
  private queue: BetterQueue<OrchestratorEvent>;

  constructor(worker: QueueWorker, concurrency: number) {
    this.queue = new BetterQueue<OrchestratorEvent>(
      (task: OrchestratorEvent, cb: (error?: unknown) => void) => {
        worker(task)
          .then(() => cb())
          .catch((err: unknown) => cb(err));
      },
      { concurrent: concurrency }
    );
  }

  push(event: OrchestratorEvent): void {
    this.queue.push(event);
  }

  on(event: "drain", cb: () => void): void {
    this.queue.on(event, cb);
  }

  destroy(): void {
    this.queue.destroy(() => {});
  }
}

export class MemoryQueueFactory implements TaskQueueFactory {
  create(name: string, worker: QueueWorker, concurrency?: number): TaskQueue {
    const resolvedConcurrency = concurrency ?? config.queueConcurrency;
    return new MemoryQueue(worker, resolvedConcurrency);
  }
}

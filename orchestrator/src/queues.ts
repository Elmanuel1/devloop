import type { TaskQueue, TaskQueueFactory, QueueWorker, OrchestratorEvent } from "./types.ts";
import { createLogger } from "./utils/logger.ts";

const log = createLogger();

export interface QueueWorkers {
  architect?: QueueWorker;
  codeWriter?: QueueWorker;
  reviewer?: QueueWorker;
}

export interface Queues {
  architect: TaskQueue;
  codeWriter: TaskQueue;
  reviewer: TaskQueue;
  destroy(): void;
}

function noopWorker(name: string): QueueWorker {
  return async (_event: OrchestratorEvent): Promise<void> => {
    log.warn(`No worker configured for queue: ${name}`, { eventType: _event.type });
  };
}

export function createQueues(factory: TaskQueueFactory, workers?: QueueWorkers): Queues {
  const architect = factory.create(
    "architect",
    workers?.architect ?? noopWorker("architect")
  );

  const codeWriter = factory.create(
    "code_writer",
    workers?.codeWriter ?? noopWorker("code_writer")
  );

  const reviewer = factory.create(
    "reviewer",
    workers?.reviewer ?? noopWorker("reviewer")
  );

  return {
    architect,
    codeWriter,
    reviewer,
    destroy() {
      architect.destroy();
      codeWriter.destroy();
      reviewer.destroy();
    },
  };
}

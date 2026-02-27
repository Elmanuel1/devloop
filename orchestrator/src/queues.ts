import type { TaskQueue, TaskQueueFactory, QueueWorker, OrchestratorEvent } from "./types.ts";
import { config } from "./config.ts";
import { createLogger } from "./utils/logger.ts";

const log = createLogger();

export interface QueueWorkers {
  architect?: QueueWorker;
  codeWriter?: QueueWorker;
  reviewer?: QueueWorker;
  orchestrator?: QueueWorker;
}

export interface Queues {
  architect: TaskQueue;
  codeWriter: TaskQueue;
  reviewer: TaskQueue;
  orchestrator: TaskQueue;
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
    workers?.architect ?? noopWorker("architect"),
    config.queueConcurrency.architect
  );

  const codeWriter = factory.create(
    "codeWriter",
    workers?.codeWriter ?? noopWorker("codeWriter"),
    config.queueConcurrency.codeWriter
  );

  const reviewer = factory.create(
    "reviewer",
    workers?.reviewer ?? noopWorker("reviewer"),
    config.queueConcurrency.reviewer
  );

  const orchestrator = factory.create(
    "orchestrator",
    workers?.orchestrator ?? noopWorker("orchestrator"),
    config.queueConcurrency.orchestrator
  );

  return {
    architect,
    codeWriter,
    reviewer,
    orchestrator,
    destroy() {
      architect.destroy();
      codeWriter.destroy();
      reviewer.destroy();
      orchestrator.destroy();
    },
  };
}

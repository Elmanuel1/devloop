import { describe, test, expect, mock, beforeEach } from "bun:test";
import { startConfluencePolling } from "../../src/polling/confluence.ts";
import type { ConfluenceClient, ConfluencePage, OrchestratorEvent } from "../../src/types.ts";

function makePage(id: string, title: string): ConfluencePage {
  return { id, title, version: 1 };
}

function makeMockConfluence(overrides: Partial<ConfluenceClient> = {}): ConfluenceClient {
  return {
    createPage: mock(async () => makePage("p1", "test")),
    updatePage: mock(async () => makePage("p1", "test")),
    findPage: mock(async () => null),
    getContentState: mock(async () => null),
    setContentState: mock(async () => undefined),
    getPagesInReview: mock(async () => []),
    getNewComments: mock(async () => []),
    ...overrides,
  };
}

function waitMs(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

describe("startConfluencePolling", () => {
  test("returns a stop function", () => {
    const confluence = makeMockConfluence();
    const result = startConfluencePolling(confluence, () => {}, 100000);
    expect(typeof result.stop).toBe("function");
    result.stop();
  });

  test("polls confluence at the given interval and dispatches approval events", async () => {
    const dispatched: OrchestratorEvent[] = [];

    const confluence = makeMockConfluence({
      getPagesInReview: mock(async () => [
        makePage("page-1", "[design-uuid-001] Feature Name"),
      ]),
      getContentState: mock(async () => "approved"),
      getNewComments: mock(async () => []),
    });

    const poller = startConfluencePolling(
      confluence,
      (event) => dispatched.push(event),
      50 // 50ms interval for fast testing
    );

    await waitMs(120);
    poller.stop();

    expect(dispatched.length).toBeGreaterThanOrEqual(1);
    expect(dispatched[0].type).toBe("page:approved");
    expect((dispatched[0] as { designId: string }).designId).toBe("design-uuid-001");
  });

  test("dispatches comment events for new comments", async () => {
    const dispatched: OrchestratorEvent[] = [];

    const confluence = makeMockConfluence({
      getPagesInReview: mock(async () => [
        makePage("page-2", "[design-uuid-002] Another Feature"),
      ]),
      getContentState: mock(async () => null),
      getNewComments: mock(async () => [
        { id: "c1", body: "Great work!", author: "alice", createdAt: new Date().toISOString() },
      ]),
    });

    const poller = startConfluencePolling(
      confluence,
      (event) => dispatched.push(event),
      50
    );

    await waitMs(120);
    poller.stop();

    const commentEvents = dispatched.filter((e) => e.type === "page:comment");
    expect(commentEvents.length).toBeGreaterThanOrEqual(1);
    expect((commentEvents[0] as { comments: string[] }).comments).toEqual(["Great work!"]);
  });

  test("stops polling after stop() is called", async () => {
    const dispatched: OrchestratorEvent[] = [];
    let callCount = 0;

    const confluence = makeMockConfluence({
      getPagesInReview: mock(async () => {
        callCount++;
        return [makePage("page-3", "[design-uuid-003] Test")];
      }),
      getContentState: mock(async () => "approved"),
      getNewComments: mock(async () => []),
    });

    const poller = startConfluencePolling(
      confluence,
      (event) => dispatched.push(event),
      50
    );

    await waitMs(80);
    const countBeforeStop = callCount;
    poller.stop();

    await waitMs(100);
    const countAfterStop = callCount;

    // After stop, no new calls should happen
    expect(countAfterStop).toBe(countBeforeStop);
  });

  test("errors during polling are caught and do not crash", async () => {
    const dispatched: OrchestratorEvent[] = [];
    let callCount = 0;

    const confluence = makeMockConfluence({
      getPagesInReview: mock(async () => {
        callCount++;
        if (callCount === 1) throw new Error("Network error");
        return [];
      }),
      getContentState: mock(async () => null),
      getNewComments: mock(async () => []),
    });

    const poller = startConfluencePolling(
      confluence,
      (event) => dispatched.push(event),
      50
    );

    // Should not throw
    await waitMs(130);
    poller.stop();

    // Polling should continue after the error
    expect(callCount).toBeGreaterThanOrEqual(2);
  });

  test("pages without extractable designId are skipped", async () => {
    const dispatched: OrchestratorEvent[] = [];

    const confluence = makeMockConfluence({
      getPagesInReview: mock(async () => [
        makePage("page-4", "A page with no design ID"),
      ]),
      getContentState: mock(async () => "approved"),
      getNewComments: mock(async () => []),
    });

    const poller = startConfluencePolling(
      confluence,
      (event) => dispatched.push(event),
      50
    );

    await waitMs(120);
    poller.stop();

    expect(dispatched).toHaveLength(0);
  });
});

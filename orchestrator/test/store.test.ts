import { describe, test, expect, beforeEach } from "bun:test";
import { Store } from "../src/store.ts";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const MIGRATIONS_DIR = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "store",
  "migrations"
);

async function createTestStore(): Promise<Store> {
  const store = new Store(":memory:", 3);
  await store.runMigrations(MIGRATIONS_DIR);
  return store;
}

function makeDesign(overrides: Partial<{
  slackChannel: string;
  slackThreadTs: string;
  requestedBy: string;
  requestText: string;
}> = {}) {
  return {
    slackChannel: overrides.slackChannel ?? "C12345",
    slackThreadTs: overrides.slackThreadTs ?? "1234567890.000001",
    requestedBy: overrides.requestedBy ?? "U99999",
    requestText: overrides.requestText ?? "Build me something awesome",
  };
}

describe("Store — design CRUD", () => {
  let store: Store;

  beforeEach(async () => {
    store = await createTestStore();
  });

  test("createDesign returns a design with correct fields", () => {
    const data = makeDesign();
    const record = store.createDesign(data);

    expect(record.id).toBeTruthy();
    expect(record.slack_channel).toBe(data.slackChannel);
    expect(record.slack_thread_ts).toBe(data.slackThreadTs);
    expect(record.requested_by).toBe(data.requestedBy);
    expect(record.request_text).toBe(data.requestText);
    expect(record.status).toBe("requested");
    expect(record.confluence_page_id).toBeNull();
    expect(record.jira_epic_key).toBeNull();
    expect(record.created_at).toBeTruthy();
    expect(record.updated_at).toBeTruthy();
  });

  test("getDesign returns the design by id", () => {
    const record = store.createDesign(makeDesign());
    const fetched = store.getDesign(record.id);

    expect(fetched).not.toBeNull();
    expect(fetched!.id).toBe(record.id);
  });

  test("getDesign returns null for unknown id", () => {
    const result = store.getDesign("non-existent-id");
    expect(result).toBeNull();
  });

  test("updateDesignStatus changes the status field", () => {
    const record = store.createDesign(makeDesign());
    store.updateDesignStatus(record.id, "designing");

    const updated = store.getDesign(record.id);
    expect(updated!.status).toBe("designing");
  });

  test("setConfluencePageId stores the page id", () => {
    const record = store.createDesign(makeDesign());
    store.setConfluencePageId(record.id, "page-abc-123");

    const updated = store.getDesign(record.id);
    expect(updated!.confluence_page_id).toBe("page-abc-123");
  });

  test("setJiraEpicKey stores the epic key", () => {
    const record = store.createDesign(makeDesign());
    store.setJiraEpicKey(record.id, "TOS-42");

    const updated = store.getDesign(record.id);
    expect(updated!.jira_epic_key).toBe("TOS-42");
  });

  test("listDesignsByStatus returns only matching designs", () => {
    const d1 = store.createDesign(makeDesign({ requestText: "req 1" }));
    const d2 = store.createDesign(makeDesign({ requestText: "req 2" }));
    store.createDesign(makeDesign({ requestText: "req 3" }));

    store.updateDesignStatus(d1.id, "designing");
    store.updateDesignStatus(d2.id, "designing");

    const designing = store.listDesignsByStatus("designing");
    expect(designing).toHaveLength(2);

    const requested = store.listDesignsByStatus("requested");
    expect(requested).toHaveLength(1);
  });

  test("listDesignsByStatus returns empty array when no match", () => {
    store.createDesign(makeDesign());
    const result = store.listDesignsByStatus("complete");
    expect(result).toHaveLength(0);
  });
});

describe("Store — design_output CRUD", () => {
  let store: Store;
  let designId: string;

  beforeEach(async () => {
    store = await createTestStore();
    designId = store.createDesign(makeDesign()).id;
  });

  test("createDesignOutput returns a record with correct fields", () => {
    const record = store.createDesignOutput({
      designId,
      stage: "architect",
      agent: "architect-agent",
      output: "The plan...",
      costUsd: 0.05,
      durationMs: 1500,
    });

    expect(record.id).toBeTruthy();
    expect(record.design_id).toBe(designId);
    expect(record.stage).toBe("architect");
    expect(record.agent).toBe("architect-agent");
    expect(record.output).toBe("The plan...");
    expect(record.cost_usd).toBe(0.05);
    expect(record.duration_ms).toBe(1500);
    expect(record.created_at).toBeTruthy();
  });

  test("createDesignOutput handles missing optional fields", () => {
    const record = store.createDesignOutput({
      designId,
      stage: "architect",
      agent: "architect-agent",
      output: "output",
    });

    expect(record.cost_usd).toBeNull();
    expect(record.duration_ms).toBeNull();
  });

  test("getDesignOutputs returns all outputs for a design", () => {
    store.createDesignOutput({ designId, stage: "architect", agent: "a", output: "out1" });
    store.createDesignOutput({ designId, stage: "code_writer", agent: "b", output: "out2" });

    const outputs = store.getDesignOutputs(designId);
    expect(outputs).toHaveLength(2);
  });

  test("getDesignOutputs returns empty array for unknown designId", () => {
    const result = store.getDesignOutputs("unknown");
    expect(result).toHaveLength(0);
  });

  test("getDesignOutputsByStage filters by stage", () => {
    store.createDesignOutput({ designId, stage: "architect", agent: "a", output: "out1" });
    store.createDesignOutput({ designId, stage: "architect", agent: "a", output: "out2" });
    store.createDesignOutput({ designId, stage: "code_writer", agent: "b", output: "out3" });

    const architectOutputs = store.getDesignOutputsByStage(designId, "architect");
    expect(architectOutputs).toHaveLength(2);

    const codeWriterOutputs = store.getDesignOutputsByStage(designId, "code_writer");
    expect(codeWriterOutputs).toHaveLength(1);

    const reviewerOutputs = store.getDesignOutputsByStage(designId, "reviewer");
    expect(reviewerOutputs).toHaveLength(0);
  });
});

describe("Store — pr_state CRUD", () => {
  let store: Store;
  let designId: string;

  beforeEach(async () => {
    store = await createTestStore();
    designId = store.createDesign(makeDesign()).id;
  });

  test("createPRState returns a record with correct fields", () => {
    const record = store.createPRState({
      designId,
      prNumber: 42,
      branch: "feature/TOS-1-something",
      jiraSubtaskKey: "TOS-43",
    });

    expect(record.id).toBeTruthy();
    expect(record.design_id).toBe(designId);
    expect(record.pr_number).toBe(42);
    expect(record.branch).toBe("feature/TOS-1-something");
    expect(record.jira_subtask_key).toBe("TOS-43");
    expect(record.status).toBe("open");
    expect(record.ci_attempts).toBe(0);
    expect(record.review_attempts).toBe(0);
    expect(record.created_at).toBeTruthy();
    expect(record.updated_at).toBeTruthy();
  });

  test("createPRState works without jiraSubtaskKey", () => {
    const record = store.createPRState({
      designId,
      prNumber: 10,
      branch: "feature/branch",
    });

    expect(record.jira_subtask_key).toBeNull();
  });

  test("getPRState returns the record by prNumber", () => {
    store.createPRState({ designId, prNumber: 55, branch: "feature/x" });

    const fetched = store.getPRState(55);
    expect(fetched).not.toBeNull();
    expect(fetched!.pr_number).toBe(55);
  });

  test("getPRState returns null for unknown prNumber", () => {
    const result = store.getPRState(9999);
    expect(result).toBeNull();
  });

  test("getPRStatesByDesign returns all PRs for a design", () => {
    store.createPRState({ designId, prNumber: 1, branch: "feature/a" });
    store.createPRState({ designId, prNumber: 2, branch: "feature/b" });

    const d2 = store.createDesign(makeDesign()).id;
    store.createPRState({ designId: d2, prNumber: 3, branch: "feature/c" });

    const prs = store.getPRStatesByDesign(designId);
    expect(prs).toHaveLength(2);
    expect(prs.map((p) => p.pr_number)).toEqual([1, 2]);
  });

  test("updatePRStatus changes the status", () => {
    store.createPRState({ designId, prNumber: 7, branch: "feature/y" });
    store.updatePRStatus(7, "ci_passed");

    const fetched = store.getPRState(7);
    expect(fetched!.status).toBe("ci_passed");
  });

  test("incrementCIAttempts increments by 1 each call", () => {
    store.createPRState({ designId, prNumber: 8, branch: "feature/z" });

    store.incrementCIAttempts(8);
    expect(store.getPRState(8)!.ci_attempts).toBe(1);

    store.incrementCIAttempts(8);
    expect(store.getPRState(8)!.ci_attempts).toBe(2);
  });

  test("incrementReviewAttempts increments by 1 each call", () => {
    store.createPRState({ designId, prNumber: 9, branch: "feature/w" });

    store.incrementReviewAttempts(9);
    expect(store.getPRState(9)!.review_attempts).toBe(1);

    store.incrementReviewAttempts(9);
    expect(store.getPRState(9)!.review_attempts).toBe(2);
  });
});

describe("Store — queries", () => {
  let store: Store;
  let designId: string;

  beforeEach(async () => {
    store = await createTestStore();
    designId = store.createDesign(makeDesign()).id;
  });

  test("checkReadyForHuman returns true when ci_passed and review_attempts < maxRetries", () => {
    store.createPRState({ designId, prNumber: 100, branch: "feature/a" });
    store.updatePRStatus(100, "ci_passed");

    expect(store.checkReadyForHuman(100)).toBe(true);
  });

  test("checkReadyForHuman returns false when status is not ci_passed", () => {
    store.createPRState({ designId, prNumber: 101, branch: "feature/b" });
    store.updatePRStatus(101, "in_review");

    expect(store.checkReadyForHuman(101)).toBe(false);
  });

  test("checkReadyForHuman returns false when review_attempts >= maxRetries", () => {
    store.createPRState({ designId, prNumber: 102, branch: "feature/c" });
    store.updatePRStatus(102, "ci_passed");

    // max retries is 3
    store.incrementReviewAttempts(102);
    store.incrementReviewAttempts(102);
    store.incrementReviewAttempts(102);

    expect(store.checkReadyForHuman(102)).toBe(false);
  });

  test("checkReadyForHuman returns false for unknown prNumber", () => {
    expect(store.checkReadyForHuman(9999)).toBe(false);
  });

  test("checkAllSiblingsMerged returns true when all PRs are merged", () => {
    store.createPRState({ designId, prNumber: 200, branch: "feature/d" });
    store.createPRState({ designId, prNumber: 201, branch: "feature/e" });

    store.updatePRStatus(200, "merged");
    store.updatePRStatus(201, "merged");

    expect(store.checkAllSiblingsMerged(designId)).toBe(true);
  });

  test("checkAllSiblingsMerged returns false when some PRs are not merged", () => {
    store.createPRState({ designId, prNumber: 202, branch: "feature/f" });
    store.createPRState({ designId, prNumber: 203, branch: "feature/g" });

    store.updatePRStatus(202, "merged");
    store.updatePRStatus(203, "approved");

    expect(store.checkAllSiblingsMerged(designId)).toBe(false);
  });

  test("checkAllSiblingsMerged returns false when no PRs exist", () => {
    expect(store.checkAllSiblingsMerged(designId)).toBe(false);
  });
});

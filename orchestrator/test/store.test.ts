import { describe, test, expect, beforeEach } from "bun:test";
import { Store } from "../src/store/index.ts";
import { join, dirname } from "path";
import { fileURLToPath } from "url";

const MIGRATIONS_DIR = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "store",
  "migrations"
);

async function createTestStore(): Promise<Store> {
  const store = new Store(":memory:");
  await store.migrator.runMigrations(MIGRATIONS_DIR);
  return store;
}

describe("Store — design CRUD", () => {
  let store: Store;

  beforeEach(async () => {
    store = await createTestStore();
  });

  test("createDesign returns a design with correct fields", () => {
    const record = store.designs.createDesign({ description: "Build something awesome" });

    expect(record.id).toBeTruthy();
    expect(record.description).toBe("Build something awesome");
    expect(record.stage).toBe("design");
    expect(record.status).toBe("running");
    expect(record.page_id).toBeNull();
    expect(record.parent_key).toBeNull();
    expect(record.review_attempts).toBe(0);
    expect(record.created_at).toBeTruthy();
    expect(record.updated_at).toBeTruthy();
  });

  test("createDesign uses defaults when no data provided", () => {
    const record = store.designs.createDesign({});

    expect(record.stage).toBe("design");
    expect(record.status).toBe("running");
    expect(record.description).toBeNull();
  });

  test("getDesign returns the design by id", () => {
    const record = store.designs.createDesign({ description: "test" });
    const fetched = store.designs.getDesign(record.id);

    expect(fetched).not.toBeNull();
    expect(fetched!.id).toBe(record.id);
  });

  test("getDesign returns null for unknown id", () => {
    const result = store.designs.getDesign("non-existent-id");
    expect(result).toBeNull();
  });

  test("updateDesignStatus changes the status field", () => {
    const record = store.designs.createDesign({});
    store.designs.updateDesignStatus(record.id, "approved");

    const updated = store.designs.getDesign(record.id);
    expect(updated!.status).toBe("approved");
  });

  test("setPageId stores the page id", () => {
    const record = store.designs.createDesign({});
    store.designs.setPageId(record.id, "page-abc-123");

    const updated = store.designs.getDesign(record.id);
    expect(updated!.page_id).toBe("page-abc-123");
  });

  test("setParentKey stores the parent key", () => {
    const record = store.designs.createDesign({});
    store.designs.setParentKey(record.id, "TOS-42");

    const updated = store.designs.getDesign(record.id);
    expect(updated!.parent_key).toBe("TOS-42");
  });

  test("incrementReviewAttempts increments by 1 each call", () => {
    const record = store.designs.createDesign({});
    expect(record.review_attempts).toBe(0);

    store.designs.incrementReviewAttempts(record.id);
    expect(store.designs.getDesign(record.id)!.review_attempts).toBe(1);

    store.designs.incrementReviewAttempts(record.id);
    expect(store.designs.getDesign(record.id)!.review_attempts).toBe(2);
  });

  test("listDesignsByStatus returns only matching designs", () => {
    const d1 = store.designs.createDesign({ description: "req 1" });
    const d2 = store.designs.createDesign({ description: "req 2" });
    store.designs.createDesign({ description: "req 3" });

    store.designs.updateDesignStatus(d1.id, "approved");
    store.designs.updateDesignStatus(d2.id, "approved");

    const approved = store.designs.listDesignsByStatus("approved");
    expect(approved).toHaveLength(2);

    const running = store.designs.listDesignsByStatus("running");
    expect(running).toHaveLength(1);
  });

  test("listDesignsByStatus returns empty array when no match", () => {
    store.designs.createDesign({});
    const result = store.designs.listDesignsByStatus("complete");
    expect(result).toHaveLength(0);
  });
});

describe("Store — design_output CRUD", () => {
  let store: Store;
  let designId: string;

  beforeEach(async () => {
    store = await createTestStore();
    designId = store.designs.createDesign({ description: "test design" }).id;
  });

  test("createDesignOutput returns a record with correct fields", () => {
    const record = store.designs.createDesignOutput(designId, "design_doc", "/designs/abc/design/design_doc.md");

    expect(record.design_id).toBe(designId);
    expect(record.output_key).toBe("design_doc");
    expect(record.output_path).toBe("/designs/abc/design/design_doc.md");
  });

  test("getDesignOutputs returns all outputs for a design", () => {
    store.designs.createDesignOutput(designId, "design_doc", "/designs/abc/design/design_doc.md");
    store.designs.createDesignOutput(designId, "design_doc_r1", "/designs/abc/design/design_doc.r1.md");

    const outputs = store.designs.getDesignOutputs(designId);
    expect(outputs).toHaveLength(2);
  });

  test("getDesignOutputs returns empty array for unknown designId", () => {
    const result = store.designs.getDesignOutputs("unknown");
    expect(result).toHaveLength(0);
  });

  test("getDesignOutputByKey returns the specific output", () => {
    store.designs.createDesignOutput(designId, "design_doc", "/designs/abc/design/design_doc.md");
    store.designs.createDesignOutput(designId, "design_doc_r1", "/designs/abc/design/design_doc.r1.md");

    const record = store.designs.getDesignOutputByKey(designId, "design_doc");
    expect(record).not.toBeNull();
    expect(record!.output_key).toBe("design_doc");
    expect(record!.output_path).toBe("/designs/abc/design/design_doc.md");
  });

  test("getDesignOutputByKey returns null for unknown key", () => {
    const result = store.designs.getDesignOutputByKey(designId, "missing_key");
    expect(result).toBeNull();
  });
});

describe("Store — pr_state CRUD", () => {
  let store: Store;
  let designId: string;

  beforeEach(async () => {
    store = await createTestStore();
    designId = store.designs.createDesign({ description: "test design" }).id;
  });

  test("createPRState returns a record with correct fields", () => {
    const record = store.prStates.createPRState({
      prNumber: 42,
      designId,
      stage: "implementation",
      issueKey: "TOS-43",
      parentKey: "TOS-40",
      featureSlug: "payments",
    });

    expect(record.pr_number).toBe(42);
    expect(record.design_id).toBe(designId);
    expect(record.stage).toBe("implementation");
    expect(record.issue_key).toBe("TOS-43");
    expect(record.parent_key).toBe("TOS-40");
    expect(record.feature_slug).toBe("payments");
    expect(record.ci_status).toBe("pending");
    expect(record.review_status).toBe("pending");
    expect(record.ci_attempts).toBe(0);
    expect(record.review_attempts).toBe(0);
    expect(record.created_at).toBeTruthy();
    expect(record.updated_at).toBeTruthy();
  });

  test("createPRState works without optional fields", () => {
    const record = store.prStates.createPRState({
      prNumber: 10,
      designId,
      stage: "implementation",
    });

    expect(record.issue_key).toBeNull();
    expect(record.parent_key).toBeNull();
    expect(record.feature_slug).toBeNull();
  });

  test("getPRState returns the record by prNumber", () => {
    store.prStates.createPRState({ prNumber: 55, designId, stage: "implementation" });

    const fetched = store.prStates.getPRState(55);
    expect(fetched).not.toBeNull();
    expect(fetched!.pr_number).toBe(55);
  });

  test("getPRState returns null for unknown prNumber", () => {
    const result = store.prStates.getPRState(9999);
    expect(result).toBeNull();
  });

  test("getPRStatesByDesign returns all PRs for a design", () => {
    store.prStates.createPRState({ prNumber: 1, designId, stage: "implementation" });
    store.prStates.createPRState({ prNumber: 2, designId, stage: "implementation" });

    const d2 = store.designs.createDesign({ description: "other design" }).id;
    store.prStates.createPRState({ prNumber: 3, designId: d2, stage: "implementation" });

    const prs = store.prStates.getPRStatesByDesign(designId);
    expect(prs).toHaveLength(2);
    expect(prs.map((p) => p.pr_number)).toEqual([1, 2]);
  });

  test("updatePRStage changes the stage", () => {
    store.prStates.createPRState({ prNumber: 7, designId, stage: "implementation" });
    store.prStates.updatePRStage(7, "merged");

    const fetched = store.prStates.getPRState(7);
    expect(fetched!.stage).toBe("merged");
  });

  test("updateCIStatus changes ci_status", () => {
    store.prStates.createPRState({ prNumber: 20, designId, stage: "implementation" });
    store.prStates.updateCIStatus(20, "passing");

    const fetched = store.prStates.getPRState(20);
    expect(fetched!.ci_status).toBe("passing");
  });

  test("updateReviewStatus changes review_status", () => {
    store.prStates.createPRState({ prNumber: 21, designId, stage: "implementation" });
    store.prStates.updateReviewStatus(21, "passing");

    const fetched = store.prStates.getPRState(21);
    expect(fetched!.review_status).toBe("passing");
  });

  test("incrementCIAttempts increments by 1 each call", () => {
    store.prStates.createPRState({ prNumber: 8, designId, stage: "implementation" });

    store.prStates.incrementCIAttempts(8);
    expect(store.prStates.getPRState(8)!.ci_attempts).toBe(1);

    store.prStates.incrementCIAttempts(8);
    expect(store.prStates.getPRState(8)!.ci_attempts).toBe(2);
  });

  test("incrementPRReviewAttempts increments by 1 each call", () => {
    store.prStates.createPRState({ prNumber: 9, designId, stage: "implementation" });

    store.prStates.incrementPRReviewAttempts(9);
    expect(store.prStates.getPRState(9)!.review_attempts).toBe(1);

    store.prStates.incrementPRReviewAttempts(9);
    expect(store.prStates.getPRState(9)!.review_attempts).toBe(2);
  });
});

describe("Store — queries", () => {
  let store: Store;
  let designId: string;

  beforeEach(async () => {
    store = await createTestStore();
    designId = store.designs.createDesign({ description: "test design" }).id;
  });

  test("checkReadyForHuman returns true when ci_status and review_status are both passing", () => {
    store.prStates.createPRState({ prNumber: 100, designId, stage: "implementation" });
    store.prStates.updateCIStatus(100, "passing");
    store.prStates.updateReviewStatus(100, "passing");

    expect(store.prStates.checkReadyForHuman(100)).toBe(true);
  });

  test("checkReadyForHuman returns false when ci_status is not passing", () => {
    store.prStates.createPRState({ prNumber: 101, designId, stage: "implementation" });
    store.prStates.updateReviewStatus(101, "passing");

    expect(store.prStates.checkReadyForHuman(101)).toBe(false);
  });

  test("checkReadyForHuman returns false when review_status is not passing", () => {
    store.prStates.createPRState({ prNumber: 102, designId, stage: "implementation" });
    store.prStates.updateCIStatus(102, "passing");

    expect(store.prStates.checkReadyForHuman(102)).toBe(false);
  });

  test("checkReadyForHuman returns false when neither status is passing", () => {
    store.prStates.createPRState({ prNumber: 103, designId, stage: "implementation" });

    expect(store.prStates.checkReadyForHuman(103)).toBe(false);
  });

  test("checkReadyForHuman returns false for unknown prNumber", () => {
    expect(store.prStates.checkReadyForHuman(9999)).toBe(false);
  });

  test("checkAllSiblingsMerged returns true when all PRs have stage merged", () => {
    store.prStates.createPRState({ prNumber: 200, designId, stage: "implementation" });
    store.prStates.createPRState({ prNumber: 201, designId, stage: "implementation" });

    store.prStates.updatePRStage(200, "merged");
    store.prStates.updatePRStage(201, "merged");

    expect(store.prStates.checkAllSiblingsMerged(designId)).toBe(true);
  });

  test("checkAllSiblingsMerged returns false when some PRs are not merged", () => {
    store.prStates.createPRState({ prNumber: 202, designId, stage: "implementation" });
    store.prStates.createPRState({ prNumber: 203, designId, stage: "implementation" });

    store.prStates.updatePRStage(202, "merged");
    // 203 remains at "implementation"

    expect(store.prStates.checkAllSiblingsMerged(designId)).toBe(false);
  });

  test("checkAllSiblingsMerged returns false when no PRs exist", () => {
    expect(store.prStates.checkAllSiblingsMerged(designId)).toBe(false);
  });
});

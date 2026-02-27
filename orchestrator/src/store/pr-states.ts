import { Database } from "bun:sqlite";
import type { PRStateRecord } from "../types.ts";

export interface CreatePRStateData {
  prNumber: number;
  designId: string;
  stage: string;
  issueKey?: string;
  parentKey?: string;
  featureSlug?: string;
}

export class PRStateRepository {
  private db: Database;

  constructor(db: Database) {
    this.db = db;
  }

  createPRState(data: CreatePRStateData): PRStateRecord {
    return this.db
      .query(
        `INSERT INTO pr_state
          (pr_number, design_id, stage, issue_key, parent_key, feature_slug)
         VALUES (?, ?, ?, ?, ?, ?)
         RETURNING *`
      )
      .get(
        data.prNumber,
        data.designId,
        data.stage,
        data.issueKey ?? null,
        data.parentKey ?? null,
        data.featureSlug ?? null
      ) as PRStateRecord;
  }

  getPRState(prNumber: number): PRStateRecord | null {
    return (
      (this.db
        .query("SELECT * FROM pr_state WHERE pr_number = ?")
        .get(prNumber) as PRStateRecord | undefined) ?? null
    );
  }

  getPRStatesByDesign(designId: string): PRStateRecord[] {
    return this.db
      .query("SELECT * FROM pr_state WHERE design_id = ? ORDER BY created_at ASC")
      .all(designId) as PRStateRecord[];
  }

  updatePRStage(prNumber: number, stage: string): void {
    this.db
      .query("UPDATE pr_state SET stage = ?, updated_at = datetime('now') WHERE pr_number = ?")
      .run(stage, prNumber);
  }

  updateCIStatus(prNumber: number, status: string): void {
    this.db
      .query("UPDATE pr_state SET ci_status = ?, updated_at = datetime('now') WHERE pr_number = ?")
      .run(status, prNumber);
  }

  updateReviewStatus(prNumber: number, status: string): void {
    this.db
      .query("UPDATE pr_state SET review_status = ?, updated_at = datetime('now') WHERE pr_number = ?")
      .run(status, prNumber);
  }

  incrementCIAttempts(prNumber: number): void {
    this.db
      .query(
        "UPDATE pr_state SET ci_attempts = ci_attempts + 1, updated_at = datetime('now') WHERE pr_number = ?"
      )
      .run(prNumber);
  }

  incrementPRReviewAttempts(prNumber: number): void {
    this.db
      .query(
        "UPDATE pr_state SET review_attempts = review_attempts + 1, updated_at = datetime('now') WHERE pr_number = ?"
      )
      .run(prNumber);
  }

  checkReadyForHuman(prNumber: number): boolean {
    const row = this.db
      .query(
        "SELECT ci_status, review_status FROM pr_state WHERE pr_number = ?"
      )
      .get(prNumber) as { ci_status: string; review_status: string } | undefined;

    if (!row) {
      return false;
    }

    return row.ci_status === "passing" && row.review_status === "passing";
  }

  checkAllSiblingsMerged(designId: string): boolean {
    const rows = this.db
      .query("SELECT stage FROM pr_state WHERE design_id = ?")
      .all(designId) as Array<{ stage: string }>;

    if (rows.length === 0) {
      return false;
    }

    return rows.every((r) => r.stage === "merged");
  }
}

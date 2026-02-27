import { Database } from "bun:sqlite";
import type { DesignRecord, DesignOutputRecord } from "../types.ts";

export interface CreateDesignData {
  id?: string;
  description?: string;
  stage?: string;
  status?: string;
}

export class DesignRepository {
  private db: Database;

  constructor(db: Database) {
    this.db = db;
  }

  createDesign(data: CreateDesignData): DesignRecord {
    const id = data.id ?? crypto.randomUUID();

    return this.db
      .query(
        `INSERT INTO design (id, description, stage, status)
         VALUES (?, ?, ?, ?)
         RETURNING *`
      )
      .get(
        id,
        data.description ?? null,
        data.stage ?? "design",
        data.status ?? "running"
      ) as DesignRecord;
  }

  getDesign(id: string): DesignRecord | null {
    return (
      (this.db
        .query("SELECT * FROM design WHERE id = ?")
        .get(id) as DesignRecord | undefined) ?? null
    );
  }

  updateDesignStatus(id: string, status: string): void {
    this.db
      .query("UPDATE design SET status = ?, updated_at = datetime('now') WHERE id = ?")
      .run(status, id);
  }

  setPageId(id: string, pageId: string): void {
    this.db
      .query("UPDATE design SET page_id = ?, updated_at = datetime('now') WHERE id = ?")
      .run(pageId, id);
  }

  setParentKey(id: string, parentKey: string): void {
    this.db
      .query("UPDATE design SET parent_key = ?, updated_at = datetime('now') WHERE id = ?")
      .run(parentKey, id);
  }

  incrementReviewAttempts(designId: string): void {
    this.db
      .query(
        "UPDATE design SET review_attempts = review_attempts + 1, updated_at = datetime('now') WHERE id = ?"
      )
      .run(designId);
  }

  listDesignsByStatus(status: string): DesignRecord[] {
    return this.db
      .query("SELECT * FROM design WHERE status = ? ORDER BY created_at ASC")
      .all(status) as DesignRecord[];
  }

  createDesignOutput(designId: string, outputKey: string, outputPath: string): DesignOutputRecord {
    return this.db
      .query(
        `INSERT INTO design_output (design_id, output_key, output_path)
         VALUES (?, ?, ?)
         RETURNING *`
      )
      .get(designId, outputKey, outputPath) as DesignOutputRecord;
  }

  getDesignOutputs(designId: string): DesignOutputRecord[] {
    return this.db
      .query("SELECT * FROM design_output WHERE design_id = ?")
      .all(designId) as DesignOutputRecord[];
  }

  getDesignOutputByKey(designId: string, outputKey: string): DesignOutputRecord | null {
    return (
      (this.db
        .query("SELECT * FROM design_output WHERE design_id = ? AND output_key = ?")
        .get(designId, outputKey) as DesignOutputRecord | undefined) ?? null
    );
  }
}

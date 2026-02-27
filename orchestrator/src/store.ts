import { Database } from "bun:sqlite";
import { readdir, readFile } from "fs/promises";
import { join, dirname } from "path";
import { fileURLToPath } from "url";
import { config } from "./config.ts";
import type {
  DesignRecord,
  DesignStatus,
  DesignOutputRecord,
  PRStateRecord,
  PRStatus,
} from "./types.ts";

const MIGRATIONS_DIR = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "store",
  "migrations"
);

interface CreateDesignData {
  slackChannel: string;
  slackThreadTs: string;
  requestedBy: string;
  requestText: string;
}

interface CreateDesignOutputData {
  designId: string;
  stage: string;
  agent: string;
  output: string;
  costUsd?: number;
  durationMs?: number;
}

interface CreatePRStateData {
  designId: string;
  prNumber: number;
  branch: string;
  jiraSubtaskKey?: string;
}

export class Store {
  private db: Database;
  private maxRetries: number;

  constructor(dbPath?: string, maxRetriesOverride?: number) {
    this.db = new Database(dbPath ?? config.dbPath);
    this.db.exec("PRAGMA journal_mode = WAL");
    this.db.exec("PRAGMA foreign_keys = ON");
    this.maxRetries = maxRetriesOverride ?? config.maxRetries;
  }

  async runMigrations(migrationsDir?: string): Promise<void> {
    const dir = migrationsDir ?? MIGRATIONS_DIR;

    this.db.exec(`
      CREATE TABLE IF NOT EXISTS _migrations (
        name TEXT PRIMARY KEY,
        applied_at TEXT NOT NULL
      )
    `);

    let files: string[];
    try {
      const entries = await readdir(dir);
      files = entries.filter((f) => f.endsWith(".sql")).sort();
    } catch {
      return;
    }

    for (const file of files) {
      const alreadyApplied = this.db
        .query("SELECT name FROM _migrations WHERE name = ?")
        .get(file);

      if (alreadyApplied) {
        continue;
      }

      const sql = await readFile(join(dir, file), "utf-8");

      this.db.exec("BEGIN");
      try {
        this.db.exec(sql);
        this.db
          .query("INSERT INTO _migrations (name, applied_at) VALUES (?, ?)")
          .run(file, new Date().toISOString());
        this.db.exec("COMMIT");
      } catch (err) {
        this.db.exec("ROLLBACK");
        throw err;
      }
    }
  }

  // ─── Design CRUD ───────────────────────────────────────────────────────────

  createDesign(data: CreateDesignData): DesignRecord {
    const id = crypto.randomUUID();
    const now = new Date().toISOString();

    this.db
      .query(
        `INSERT INTO design
          (id, slack_channel, slack_thread_ts, requested_by, request_text,
           confluence_page_id, jira_epic_key, status, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, NULL, NULL, 'requested', ?, ?)`
      )
      .run(id, data.slackChannel, data.slackThreadTs, data.requestedBy, data.requestText, now, now);

    return this.getDesign(id)!;
  }

  getDesign(id: string): DesignRecord | null {
    return (
      (this.db
        .query("SELECT * FROM design WHERE id = ?")
        .get(id) as DesignRecord | undefined) ?? null
    );
  }

  updateDesignStatus(id: string, status: DesignStatus): void {
    this.db
      .query("UPDATE design SET status = ?, updated_at = ? WHERE id = ?")
      .run(status, new Date().toISOString(), id);
  }

  setConfluencePageId(id: string, pageId: string): void {
    this.db
      .query("UPDATE design SET confluence_page_id = ?, updated_at = ? WHERE id = ?")
      .run(pageId, new Date().toISOString(), id);
  }

  setJiraEpicKey(id: string, epicKey: string): void {
    this.db
      .query("UPDATE design SET jira_epic_key = ?, updated_at = ? WHERE id = ?")
      .run(epicKey, new Date().toISOString(), id);
  }

  listDesignsByStatus(status: DesignStatus): DesignRecord[] {
    return this.db
      .query("SELECT * FROM design WHERE status = ? ORDER BY created_at ASC")
      .all(status) as DesignRecord[];
  }

  // ─── DesignOutput CRUD ─────────────────────────────────────────────────────

  createDesignOutput(data: CreateDesignOutputData): DesignOutputRecord {
    const id = crypto.randomUUID();
    const now = new Date().toISOString();

    this.db
      .query(
        `INSERT INTO design_output
          (id, design_id, stage, agent, output, cost_usd, duration_ms, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)`
      )
      .run(
        id,
        data.designId,
        data.stage,
        data.agent,
        data.output,
        data.costUsd ?? null,
        data.durationMs ?? null,
        now
      );

    return this.db
      .query("SELECT * FROM design_output WHERE id = ?")
      .get(id) as DesignOutputRecord;
  }

  getDesignOutputs(designId: string): DesignOutputRecord[] {
    return this.db
      .query(
        "SELECT * FROM design_output WHERE design_id = ? ORDER BY created_at ASC"
      )
      .all(designId) as DesignOutputRecord[];
  }

  getDesignOutputsByStage(designId: string, stage: string): DesignOutputRecord[] {
    return this.db
      .query(
        "SELECT * FROM design_output WHERE design_id = ? AND stage = ? ORDER BY created_at ASC"
      )
      .all(designId, stage) as DesignOutputRecord[];
  }

  // ─── PRState CRUD ──────────────────────────────────────────────────────────

  createPRState(data: CreatePRStateData): PRStateRecord {
    const id = crypto.randomUUID();
    const now = new Date().toISOString();

    this.db
      .query(
        `INSERT INTO pr_state
          (id, design_id, pr_number, branch, jira_subtask_key, status,
           ci_attempts, review_attempts, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, 'open', 0, 0, ?, ?)`
      )
      .run(
        id,
        data.designId,
        data.prNumber,
        data.branch,
        data.jiraSubtaskKey ?? null,
        now,
        now
      );

    return this.db
      .query("SELECT * FROM pr_state WHERE id = ?")
      .get(id) as PRStateRecord;
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
      .query(
        "SELECT * FROM pr_state WHERE design_id = ? ORDER BY created_at ASC"
      )
      .all(designId) as PRStateRecord[];
  }

  updatePRStatus(prNumber: number, status: PRStatus): void {
    this.db
      .query("UPDATE pr_state SET status = ?, updated_at = ? WHERE pr_number = ?")
      .run(status, new Date().toISOString(), prNumber);
  }

  incrementCIAttempts(prNumber: number): void {
    this.db
      .query(
        "UPDATE pr_state SET ci_attempts = ci_attempts + 1, updated_at = ? WHERE pr_number = ?"
      )
      .run(new Date().toISOString(), prNumber);
  }

  incrementReviewAttempts(prNumber: number): void {
    this.db
      .query(
        "UPDATE pr_state SET review_attempts = review_attempts + 1, updated_at = ? WHERE pr_number = ?"
      )
      .run(new Date().toISOString(), prNumber);
  }

  // ─── Queries ───────────────────────────────────────────────────────────────

  checkReadyForHuman(prNumber: number): boolean {
    const row = this.db
      .query(
        "SELECT status, review_attempts FROM pr_state WHERE pr_number = ?"
      )
      .get(prNumber) as { status: PRStatus; review_attempts: number } | undefined;

    if (!row) {
      return false;
    }

    return row.status === "ci_passed" && row.review_attempts < this.maxRetries;
  }

  checkAllSiblingsMerged(designId: string): boolean {
    const rows = this.db
      .query("SELECT status FROM pr_state WHERE design_id = ?")
      .all(designId) as Array<{ status: PRStatus }>;

    if (rows.length === 0) {
      return false;
    }

    return rows.every((r) => r.status === "merged");
  }

  close(): void {
    this.db.close();
  }
}

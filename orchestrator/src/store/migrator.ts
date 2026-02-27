import { Database } from "bun:sqlite";
import { readdir, readFile } from "fs/promises";
import { join } from "path";

export class Migrator {
  private db: Database;

  constructor(db: Database) {
    this.db = db;
  }

  async runMigrations(migrationsDir: string): Promise<void> {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS _migrations (
        name TEXT PRIMARY KEY,
        applied_at TEXT NOT NULL
      )
    `);

    let files: string[];
    try {
      const entries = await readdir(migrationsDir);
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

      const sql = await readFile(join(migrationsDir, file), "utf-8");

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
}

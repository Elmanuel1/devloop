import { Database } from "bun:sqlite";
import { join, dirname } from "path";
import { fileURLToPath } from "url";
import { config } from "../config.ts";
import { Migrator } from "./migrator.ts";
import { DesignRepository } from "./designs.ts";
import { PRStateRepository } from "./pr-states.ts";

export { Migrator } from "./migrator.ts";
export { DesignRepository } from "./designs.ts";
export type { CreateDesignData } from "./designs.ts";
export { PRStateRepository } from "./pr-states.ts";
export type { CreatePRStateData } from "./pr-states.ts";

const MIGRATIONS_DIR = join(
  dirname(fileURLToPath(import.meta.url)),
  "..",
  "..",
  "store",
  "migrations"
);

export class Store {
  private db: Database;
  readonly migrator: Migrator;
  readonly designs: DesignRepository;
  readonly prStates: PRStateRepository;

  constructor(dbPath?: string) {
    this.db = new Database(dbPath ?? config.dbPath);
    this.db.exec("PRAGMA journal_mode = WAL");
    this.db.exec("PRAGMA foreign_keys = ON");
    this.migrator = new Migrator(this.db);
    this.designs = new DesignRepository(this.db);
    this.prStates = new PRStateRepository(this.db);
  }

  async init(migrationsDir?: string): Promise<void> {
    await this.migrator.runMigrations(migrationsDir ?? MIGRATIONS_DIR);
  }

  close(): void {
    this.db.close();
  }
}

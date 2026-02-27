CREATE TABLE IF NOT EXISTS design (
  id TEXT PRIMARY KEY,
  description TEXT,
  stage TEXT DEFAULT 'design',
  status TEXT DEFAULT 'running',
  page_id TEXT,
  parent_key TEXT,
  review_attempts INTEGER DEFAULT 0,
  created_at TEXT DEFAULT (datetime('now')),
  updated_at TEXT DEFAULT (datetime('now'))
);

CREATE TABLE IF NOT EXISTS design_output (
  design_id TEXT NOT NULL,
  output_key TEXT NOT NULL,
  output_path TEXT NOT NULL,
  PRIMARY KEY (design_id, output_key),
  FOREIGN KEY (design_id) REFERENCES design(id)
);

CREATE TABLE IF NOT EXISTS pr_state (
  pr_number INTEGER PRIMARY KEY,
  design_id TEXT NOT NULL,
  stage TEXT NOT NULL,
  issue_key TEXT,
  parent_key TEXT,
  feature_slug TEXT,
  ci_status TEXT DEFAULT 'pending',
  review_status TEXT DEFAULT 'pending',
  ci_attempts INTEGER DEFAULT 0,
  review_attempts INTEGER DEFAULT 0,
  created_at TEXT DEFAULT (datetime('now')),
  updated_at TEXT DEFAULT (datetime('now')),
  FOREIGN KEY (design_id) REFERENCES design(id)
);

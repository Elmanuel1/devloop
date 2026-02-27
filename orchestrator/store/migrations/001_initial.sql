CREATE TABLE IF NOT EXISTS design (
  id TEXT PRIMARY KEY,
  slack_channel TEXT NOT NULL,
  slack_thread_ts TEXT NOT NULL,
  requested_by TEXT NOT NULL,
  request_text TEXT NOT NULL,
  confluence_page_id TEXT,
  jira_epic_key TEXT,
  status TEXT NOT NULL DEFAULT 'requested',
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS design_output (
  id TEXT PRIMARY KEY,
  design_id TEXT NOT NULL REFERENCES design(id),
  stage TEXT NOT NULL,
  agent TEXT NOT NULL,
  output TEXT NOT NULL,
  cost_usd REAL,
  duration_ms INTEGER,
  created_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_design_output_design_id ON design_output(design_id);

CREATE TABLE IF NOT EXISTS pr_state (
  id TEXT PRIMARY KEY,
  design_id TEXT NOT NULL REFERENCES design(id),
  pr_number INTEGER NOT NULL,
  branch TEXT NOT NULL,
  jira_subtask_key TEXT,
  status TEXT NOT NULL DEFAULT 'open',
  ci_attempts INTEGER NOT NULL DEFAULT 0,
  review_attempts INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE(pr_number)
);

CREATE INDEX IF NOT EXISTS idx_pr_state_design_id ON pr_state(design_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_pr_state_pr_number ON pr_state(pr_number);

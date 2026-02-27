function env(key: string): string;
function env(key: string, fallback: string): string;
function env(key: string, fallback?: string): string {
  const value = process.env[key];
  if (value !== undefined && value !== "") {
    return value;
  }
  if (fallback !== undefined) {
    return fallback;
  }
  return "";
}

function envInt(key: string, fallback: number): number {
  const value = process.env[key];
  if (value !== undefined && value !== "") {
    const parsed = parseInt(value, 10);
    if (!isNaN(parsed)) {
      return parsed;
    }
  }
  return fallback;
}

export const config = {
  port: envInt("PORT", 3100),
  dbPath: env("DB_PATH", "./orchestrator.db"),
  queueConcurrency: {
    architect: envInt("QUEUE_CONCURRENCY_ARCHITECT", 2),
    codeWriter: envInt("QUEUE_CONCURRENCY_CODE_WRITER", 3),
    reviewer: envInt("QUEUE_CONCURRENCY_REVIEWER", 2),
    orchestrator: envInt("QUEUE_CONCURRENCY_ORCHESTRATOR", 1),
  },
  maxCiRetries: envInt("MAX_CI_RETRIES", 10),
  maxReviewRetries: envInt("MAX_REVIEW_RETRIES", 3),
  designOutputBasePath: env("DESIGN_OUTPUT_BASE_PATH", "./designs"),
  agentTimeoutMs: envInt("AGENT_TIMEOUT_MS", 3600000),
  agentHeartbeatMs: envInt("AGENT_HEARTBEAT_MS", 600000),
  confluencePollIntervalMs: envInt("CONFLUENCE_POLL_INTERVAL_MS", 60000),

  slack: {
    signingSecret: env("SLACK_SIGNING_SECRET", ""),
    webhookUrl: env("SLACK_WEBHOOK_URL", ""),
    botToken: env("SLACK_BOT_TOKEN", ""),
  },

  jira: {
    baseUrl: env("JIRA_BASE_URL", ""),
    email: env("JIRA_EMAIL", ""),
    apiToken: env("JIRA_API_TOKEN", ""),
    projectKey: env("JIRA_PROJECT_KEY", ""),
  },

  confluence: {
    baseUrl: env("CONFLUENCE_BASE_URL", ""),
    email: env("CONFLUENCE_EMAIL", ""),
    apiToken: env("CONFLUENCE_API_TOKEN", ""),
    spaceKey: env("CONFLUENCE_SPACE_KEY", ""),
  },

  github: {
    token: env("GITHUB_TOKEN", ""),
    webhookSecret: env("GITHUB_WEBHOOK_SECRET", ""),
    owner: env("GITHUB_OWNER", ""),
    repo: env("GITHUB_REPO", ""),
  },
} as const;

export type Config = typeof config;

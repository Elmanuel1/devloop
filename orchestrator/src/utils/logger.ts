export type LogLevel = "debug" | "info" | "warn" | "error";

const LOG_LEVELS: Record<LogLevel, number> = {
  debug: 0,
  info: 1,
  warn: 2,
  error: 3,
};

let currentLevel: LogLevel = "info";

export function setLogLevel(level: LogLevel): void {
  currentLevel = level;
}

export interface Logger {
  debug(msg: string, extra?: Record<string, unknown>): void;
  info(msg: string, extra?: Record<string, unknown>): void;
  warn(msg: string, extra?: Record<string, unknown>): void;
  error(msg: string, extra?: Record<string, unknown>): void;
}

function writeLog(
  level: LogLevel,
  msg: string,
  designId: string | undefined,
  extra?: Record<string, unknown>
): void {
  if (LOG_LEVELS[level] < LOG_LEVELS[currentLevel]) {
    return;
  }

  const entry: Record<string, unknown> = {
    ts: new Date().toISOString(),
    level,
    msg,
  };

  if (designId !== undefined) {
    entry.designId = designId;
  }

  if (extra !== undefined) {
    Object.assign(entry, extra);
  }

  const line = JSON.stringify(entry);

  if (level === "error" || level === "warn") {
    process.stderr.write(line + "\n");
  } else {
    process.stdout.write(line + "\n");
  }
}

export function createLogger(designId?: string): Logger {
  return {
    debug(msg: string, extra?: Record<string, unknown>): void {
      writeLog("debug", msg, designId, extra);
    },
    info(msg: string, extra?: Record<string, unknown>): void {
      writeLog("info", msg, designId, extra);
    },
    warn(msg: string, extra?: Record<string, unknown>): void {
      writeLog("warn", msg, designId, extra);
    },
    error(msg: string, extra?: Record<string, unknown>): void {
      writeLog("error", msg, designId, extra);
    },
  };
}

const log: Logger = createLogger();
export default log;

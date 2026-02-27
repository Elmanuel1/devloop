import type { ParsedAgentOutput } from "../types.ts";

/**
 * Parses the raw stdout from `claude -p --output-format json`.
 *
 * On parse failure, returns `{ result: raw }` — never throws.
 */
export function parseAgentOutput(raw: string): ParsedAgentOutput {
  try {
    const parsed: unknown = JSON.parse(raw);

    if (parsed === null || typeof parsed !== "object" || Array.isArray(parsed)) {
      return { result: raw };
    }

    const obj = parsed as Record<string, unknown>;

    const output: ParsedAgentOutput = {};

    if (typeof obj.result === "string") {
      output.result = obj.result;
    }
    if (typeof obj.cost_usd === "number") {
      output.cost_usd = obj.cost_usd;
    }
    if (typeof obj.duration_ms === "number") {
      output.duration_ms = obj.duration_ms;
    }
    if (typeof obj.duration_api_ms === "number") {
      output.duration_api_ms = obj.duration_api_ms;
    }
    if (typeof obj.num_turns === "number") {
      output.num_turns = obj.num_turns;
    }
    if (typeof obj.is_error === "boolean") {
      output.is_error = obj.is_error;
    }
    if (typeof obj.session_id === "string") {
      output.session_id = obj.session_id;
    }

    return output;
  } catch {
    return { result: raw };
  }
}

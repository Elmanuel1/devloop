import { describe, test, expect } from "bun:test";
import { parseAgentOutput } from "../../src/utils/parse-agent-output.ts";

describe("parseAgentOutput", () => {
  test("valid JSON with all ParsedAgentOutput fields — extracts all fields correctly", () => {
    const input = JSON.stringify({
      result: "Done",
      cost_usd: 0.05,
      duration_ms: 12000,
      duration_api_ms: 11500,
      num_turns: 4,
      is_error: false,
      session_id: "sess-abc-123",
    });

    const output = parseAgentOutput(input);

    expect(output.result).toBe("Done");
    expect(output.cost_usd).toBe(0.05);
    expect(output.duration_ms).toBe(12000);
    expect(output.duration_api_ms).toBe(11500);
    expect(output.num_turns).toBe(4);
    expect(output.is_error).toBe(false);
    expect(output.session_id).toBe("sess-abc-123");
  });

  test("valid JSON with partial fields — missing fields are undefined", () => {
    const input = JSON.stringify({
      result: "Partial output",
      cost_usd: 0.02,
    });

    const output = parseAgentOutput(input);

    expect(output.result).toBe("Partial output");
    expect(output.cost_usd).toBe(0.02);
    expect(output.duration_ms).toBeUndefined();
    expect(output.duration_api_ms).toBeUndefined();
    expect(output.num_turns).toBeUndefined();
    expect(output.is_error).toBeUndefined();
    expect(output.session_id).toBeUndefined();
  });

  test("valid JSON with only unknown extra fields — returns empty ParsedAgentOutput", () => {
    const input = JSON.stringify({
      unknown_field: "value",
      another: 42,
    });

    const output = parseAgentOutput(input);

    expect(output.result).toBeUndefined();
    expect(output.cost_usd).toBeUndefined();
    expect(output.duration_ms).toBeUndefined();
  });

  test("valid JSON but is_error=true is extracted correctly", () => {
    const input = JSON.stringify({
      result: "Something went wrong",
      is_error: true,
      session_id: "sess-err-789",
    });

    const output = parseAgentOutput(input);

    expect(output.result).toBe("Something went wrong");
    expect(output.is_error).toBe(true);
    expect(output.session_id).toBe("sess-err-789");
  });

  test("invalid JSON — returns { result: raw } without throwing", () => {
    const raw = "this is not json {{{";

    const output = parseAgentOutput(raw);

    expect(output.result).toBe(raw);
    expect(output.cost_usd).toBeUndefined();
  });

  test("empty string — returns { result: '' } without throwing", () => {
    const output = parseAgentOutput("");

    expect(output.result).toBe("");
    expect(Object.keys(output)).toEqual(["result"]);
  });

  test("JSON null — returns { result: raw } (graceful degradation)", () => {
    const raw = "null";

    const output = parseAgentOutput(raw);

    expect(output.result).toBe("null");
  });

  test("JSON array — returns { result: raw } (graceful degradation)", () => {
    const raw = JSON.stringify(["a", "b", "c"]);

    const output = parseAgentOutput(raw);

    expect(output.result).toBe(raw);
  });

  test("fields with wrong types are ignored — not extracted", () => {
    const input = JSON.stringify({
      result: 999,           // should be string
      cost_usd: "0.05",     // should be number
      is_error: "true",     // should be boolean
      session_id: 12345,    // should be string
    });

    const output = parseAgentOutput(input);

    // All fields have wrong types — none should be extracted
    expect(output.result).toBeUndefined();
    expect(output.cost_usd).toBeUndefined();
    expect(output.is_error).toBeUndefined();
    expect(output.session_id).toBeUndefined();
  });

  test("num_turns=0 — extracted as 0, not treated as falsy", () => {
    const input = JSON.stringify({ num_turns: 0 });

    const output = parseAgentOutput(input);

    expect(output.num_turns).toBe(0);
  });

  test("cost_usd=0 — extracted as 0, not treated as falsy", () => {
    const input = JSON.stringify({ cost_usd: 0 });

    const output = parseAgentOutput(input);

    expect(output.cost_usd).toBe(0);
  });
});

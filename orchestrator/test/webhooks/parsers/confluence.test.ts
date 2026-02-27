import { describe, test, expect } from "bun:test";
import {
  parseConfluenceApproval,
  parseConfluenceComment,
} from "../../../src/webhooks/parsers/confluence.ts";

describe("parseConfluenceApproval", () => {
  test("returns a PageApprovedEvent with correct fields", () => {
    const page = { id: "page-123", designId: "design-abc" };
    const event = parseConfluenceApproval(page);

    expect(event.type).toBe("page:approved");
    expect(event.source).toBe("confluence");
    expect(event.pageId).toBe("page-123");
    expect(event.designId).toBe("design-abc");
    expect(typeof event.id).toBe("string");
    expect(event.id.length).toBeGreaterThan(0);
  });

  test("sets raw to the page object", () => {
    const page = { id: "page-456", designId: "design-def" };
    const event = parseConfluenceApproval(page);
    expect(event.raw).toEqual(page);
  });

  test("generates unique IDs for each call", () => {
    const page = { id: "page-789", designId: "design-ghi" };
    const e1 = parseConfluenceApproval(page);
    const e2 = parseConfluenceApproval(page);
    expect(e1.id).not.toBe(e2.id);
  });
});

describe("parseConfluenceComment", () => {
  test("returns a NewCommentEvent with correct fields", () => {
    const page = { id: "page-100", designId: "design-xyz" };
    const comment = { id: "comment-1", body: "Looks great overall!", author: "alice" };
    const event = parseConfluenceComment(page, comment);

    expect(event.type).toBe("page:comment");
    expect(event.source).toBe("confluence");
    expect(event.pageId).toBe("page-100");
    expect(event.designId).toBe("design-xyz");
    expect(event.comments).toEqual(["Looks great overall!"]);
    expect(typeof event.id).toBe("string");
    expect(event.id.length).toBeGreaterThan(0);
  });

  test("sets raw to an object containing both page and comment", () => {
    const page = { id: "page-200", designId: "design-yyy" };
    const comment = { id: "comment-2", body: "Please revise section 3", author: "bob" };
    const event = parseConfluenceComment(page, comment);
    expect((event.raw as { page: unknown; comment: unknown }).page).toEqual(page);
    expect((event.raw as { page: unknown; comment: unknown }).comment).toEqual(comment);
  });

  test("comments is always an array with the comment body", () => {
    const page = { id: "page-300", designId: "design-zzz" };
    const comment = { id: "comment-3", body: "Minor nit", author: "carol" };
    const event = parseConfluenceComment(page, comment);
    expect(Array.isArray(event.comments)).toBe(true);
    expect(event.comments).toHaveLength(1);
  });

  test("generates unique IDs for each call", () => {
    const page = { id: "page-400", designId: "design-aaa" };
    const comment = { id: "comment-4", body: "Same comment", author: "dave" };
    const e1 = parseConfluenceComment(page, comment);
    const e2 = parseConfluenceComment(page, comment);
    expect(e1.id).not.toBe(e2.id);
  });
});

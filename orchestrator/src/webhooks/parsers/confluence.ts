import type { PageApprovedEvent, NewCommentEvent } from "../../types.ts";

export function parseConfluenceApproval(page: {
  id: string;
  designId: string;
}): PageApprovedEvent {
  return {
    id: crypto.randomUUID(),
    source: "confluence",
    type: "page:approved",
    raw: page,
    pageId: page.id,
    designId: page.designId,
  };
}

export function parseConfluenceComment(
  page: { id: string; designId: string },
  comment: { id: string; body: string; author: string }
): NewCommentEvent {
  return {
    id: crypto.randomUUID(),
    source: "confluence",
    type: "page:comment",
    raw: { page, comment },
    pageId: page.id,
    designId: page.designId,
    comments: [comment.body],
  };
}

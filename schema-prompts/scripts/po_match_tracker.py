#!/usr/bin/env python3
"""
Tracks which PO line items have been matched to prevent duplicates.
AI uses this to check/mark PO indices during matching.
"""
import json
import sys
import os

TRACK_FILE = "_po_matches.json"

def load_tracker(work_dir):
    """Load or initialize tracker."""
    path = os.path.join(work_dir, TRACK_FILE)
    if os.path.exists(path):
        with open(path) as f:
            return json.load(f)
    return {"usedPoIndices": [], "matches": []}

def save_tracker(work_dir, data):
    """Save tracker state."""
    path = os.path.join(work_dir, TRACK_FILE)
    with open(path, 'w') as f:
        json.dump(data, f, indent=2)

def check_available(work_dir, po_index):
    """Check if PO index is available (not yet matched)."""
    data = load_tracker(work_dir)
    available = po_index not in data["usedPoIndices"]
    print(json.dumps({"poIndex": po_index, "available": available}))
    return available

def mark_matched(work_dir, doc_index, po_index, matched_by="ai"):
    """Mark a PO index as matched to a document index."""
    data = load_tracker(work_dir)

    if po_index in data["usedPoIndices"]:
        print(json.dumps({"success": False, "error": f"PO index {po_index} already matched"}))
        return False

    data["usedPoIndices"].append(po_index)
    data["matches"].append({
        "docIndex": doc_index,
        "poIndex": po_index,
        "matchedBy": matched_by
    })
    save_tracker(work_dir, data)
    print(json.dumps({"success": True, "docIndex": doc_index, "poIndex": po_index}))
    return True

def get_matches(work_dir):
    """Get all recorded matches."""
    data = load_tracker(work_dir)
    print(json.dumps(data, indent=2))
    return data

if __name__ == '__main__':
    if len(sys.argv) < 3:
        print("Usage:")
        print("  po_match_tracker.py <work_dir> check <po_index>")
        print("  po_match_tracker.py <work_dir> mark <doc_index> <po_index> [matched_by]")
        print("  po_match_tracker.py <work_dir> list")
        sys.exit(1)

    work_dir = sys.argv[1]
    cmd = sys.argv[2]

    if cmd == "check":
        check_available(work_dir, int(sys.argv[3]))
    elif cmd == "mark":
        matched_by = sys.argv[5] if len(sys.argv) > 5 else "ai"
        mark_matched(work_dir, int(sys.argv[3]), int(sys.argv[4]), matched_by)
    elif cmd == "list":
        get_matches(work_dir)

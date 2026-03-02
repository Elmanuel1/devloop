# PR Feedback Skill

When the orchestrator routes a `pr:comment` or `pr:changes_requested` event to you, you own the full response cycle: read → classify → reply → fix if needed.

## Tools Available

- `python3 orchestrator/gh_comment.py ack --repo {repo} --pr {pr} --comment-id {id}` → posts `👀 On it — fix incoming`
- `python3 orchestrator/gh_comment.py fixed --repo {repo} --pr {pr} --comment-id {id} --sha {sha}` → posts `✅ Fixed in [sha](url)`
- `python3 orchestrator/gh_comment.py reply --repo {repo} --pr {pr} --comment-id {id} --body "..."` → custom reply

## Classify First

| Type | Signals | Action |
|------|---------|--------|
| **Bug / mistake** | "this is wrong", "should be X", clear error pointed out | ack → fix → confirm |
| **Question** | "why did you...", "what does X do" | reply directly, no code change |
| **Recommendation** | "consider using X", "you could also..." | assess → adopt if better, decline with reason if not |
| **Pushback** | "I disagree", "this approach seems wrong" | reply explaining design decision, cite approved design doc |

## Bug / Mistake Flow

```bash
# 1. Ack immediately — author knows it was seen
python3 orchestrator/gh_comment.py ack --repo Build4Africa/tosspaper --pr {pr} --comment-id {commentId}

# 2. Fix the code on the current branch
# 3. Commit
git commit -m "fix: address PR comment — {short description}"
git push

# 4. Confirm with sha
COMMIT_SHA=$(git rev-parse HEAD)
python3 orchestrator/gh_comment.py fixed --repo Build4Africa/tosspaper --pr {pr} --comment-id {commentId} --sha $COMMIT_SHA
```

## Question Flow

```bash
python3 orchestrator/gh_comment.py reply \
  --repo Build4Africa/tosspaper --pr {pr} --comment-id {commentId} \
  --body "Answer here — no code change needed."
```

## Recommendation Flow

**Adopting:** treat like a bug fix (ack → implement → push → confirm).

**Declining:**
```bash
python3 orchestrator/gh_comment.py reply \
  --repo Build4Africa/tosspaper --pr {pr} --comment-id {commentId} \
  --body "Good suggestion — keeping current approach because {reason}."
```

## Pushback Flow

```bash
python3 orchestrator/gh_comment.py reply \
  --repo Build4Africa/tosspaper --pr {pr} --comment-id {commentId} \
  --body "This is intentional — the approved design (Confluence page {id}) specifies {reason}."
```

## Multiple Comments

If `pr:changes_requested` fires with several comments, handle them in order:
1. Ack all at once with a single general post: `python3 orchestrator/gh_comment.py post --repo ... --pr ... --body "👀 Picked up {n} comments — addressing now"`
2. Fix all bugs/adopted recommendations in one commit
3. Reply individually to each comment (answer, decline, or sha confirmation)

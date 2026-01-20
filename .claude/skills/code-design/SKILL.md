---
name: code-design
description: Analyze code for architectural improvements using DRY, SOLID principles. Suggest refactoring to improve testability, reduce duplication, and improve maintainability. Use when reviewing code quality or preparing code for testing.
allowed-tools:
  - Read
  - Glob
  - Grep
  - Edit
  - Write
  - Bash
hooks:
  PostToolUse:
    - matcher: "Write|Edit"
      hooks:
        - type: command
          command: "bash $CLAUDE_PROJECT_DIR/.claude/skills/code-design/scripts/format-code.sh"
        - type: command
          command: "bash $CLAUDE_PROJECT_DIR/.claude/skills/code-design/scripts/regenerate-sources.sh"
        - type: command
          command: "bash $CLAUDE_PROJECT_DIR/.claude/skills/code-design/scripts/verify-compile.sh"
          once: true
---

# Architectural Code Design & Testability

Analyze code and suggest improvements based on SOLID principles, DRY, and testability patterns.

## When to Use This Skill

✅ Before writing tests - make code testable first
✅ Code review for architectural issues
✅ Refactoring for maintainability
✅ Reducing code duplication
✅ Breaking down large classes/methods

## Quick Reference

This skill covers four main areas:

1. **[SOLID Principles](references/SOLID.md)** - Single Responsibility, Open/Closed, Liskov Substitution, Interface Segregation, Dependency Inversion
2. **[DRY Patterns](references/DRY.md)** - Identifying and eliminating code duplication
3. **[Testability Patterns](references/TESTABILITY.md)** - Making code easy to test with mocks
4. **[Refactoring Guide](references/REFACTORING.md)** - Checklist and suggested refactorings

## How to Use This Skill

1. **Read the class** to analyze
2. **Check against principles** in the reference guides
3. **Identify violations** (duplication, SRP, testability issues)
4. **Suggest specific refactoring** with before/after code
5. **Explain testability benefit** of each change

For detailed examples and patterns, see the [reference guides](references/).

# Domain Docs

How engineering skills should consume this repository's domain documentation when exploring the codebase.

## Before exploring

- Read `CONTEXT-MAP.md` at the repository root and then each relevant module `CONTEXT.md`.
- Read system-wide ADRs in `docs/adr/` and module-scoped ADRs in `<module>/docs/adr/` when they apply.
- If these files do not exist, proceed silently. Create domain documents only when terminology or an architectural decision is actually resolved.

## Layout

This is a multi-context repository:

```
/
├── CONTEXT-MAP.md
├── docs/adr/
├── pixflow-*/
│   ├── CONTEXT.md
│   └── docs/adr/
└── docs/design-docs/
```

## Use the glossary's vocabulary

When output names a domain concept, use the term defined in the relevant `CONTEXT.md`. If the concept is not defined, reconsider whether the project already uses a different term or note the gap for `/domain-modeling`.

## Flag ADR conflicts

If output contradicts an applicable ADR, surface the conflict explicitly rather than silently overriding it.

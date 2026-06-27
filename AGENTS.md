# AGENTS.md

This project is currently in the development stage. Before contributing to development, modification, review, or planning work, always read the project documentation first so that implementation stays aligned with the existing design and execution plans.

## Required Documentation

`docs/` is the required documentation directory for this project. Before starting any development task, read the documents in this directory that are relevant to the current work.

## Documentation Structure

- `docs/design-docs/`: Project design documentation, including overall design, architecture design, and module design concepts.
- `docs/design-docs/MVP-design/`: MVP design documentation. These documents may be used only as reference material; do not copy them directly or base the new design on the MVP. The project architecture must be fully restructured to a production-grade standard and must not match the MVP architecture.
- `docs/design-docs/exec-plans/`: Current execution plans. Before starting any task, you must read the plans in this directory first to confirm the current stage goals, implementation order, and constraints.
- `docs/design-docs/module/`: Detailed design documents for individual modules. When working on or modifying a module, read the corresponding module design first.
- `docs/references/`: Reference documentation for project development, including architecture, runtime behavior, permissions, context handling, error handling, and related materials.

## Working Requirements

1. Before starting a task, first read the current execution plans in `docs/design-docs/exec-plans/`.
2. Based on the task scope, also read the relevant documents in `docs/design-docs/`, `docs/design-docs/module/`, and `docs/references/`.
3. Code implementation must follow the existing design documents and execution plans. If a requested change conflicts with the documentation, explain the conflict before proceeding and confirm the handling approach.
4. Because this project is still in the development stage, keep changes clearly scoped and avoid introducing refactors or features unrelated to the current plan.
5. When the user asks for a plan, write it by referring to [PLANS.md](PLANS.md) unless the user explicitly asks not to use that format for a lightweight plan.

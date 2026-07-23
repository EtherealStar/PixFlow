# AGENTS.md

## Project

PixFlow is an AI-assisted e-commerce image operations system. The Agent proposes work, while domain modules validate, execute, and own business state.

The project is under active development. Current execution plans and design documents are authoritative over legacy code and introductory documentation.

## Required Documentation

`docs/` is the required documentation directory for this project. Before starting any development task, first read the current execution plans in `docs/design-docs/exec-plans/` to confirm the current stage goals, implementation order, and constraints.

## Documentation Structure

For details about the `docs/design-docs/` folder structure and what each subfolder contains, read [docs/design-docs/index.md](docs/design-docs/index.md).

## Toolchain and Verification

- Backend: Java 21, Spring Boot 3, and Maven.
- Frontend: Vue 3, TypeScript, Vite, and `pnpm`.
- Infrastructure-backed tests may require Docker and Testcontainers.
- Use `pnpm`; do not substitute npm, yarn, or bun.
- Run verification commands from the repository root unless an active execution plan says otherwise.
- Follow the verification scope and order defined by the relevant execution plan.
- For affected backend modules, run static verification before tests:

      mvn -pl <affected-modules> -am -DskipTests verify
      mvn -pl <affected-modules> -am test

- For full backend verification:

      mvn -DskipTests verify
      mvn verify

- For frontend changes, run:

      pnpm --dir pixflow-web lint
      pnpm --dir pixflow-web typecheck
      pnpm --dir pixflow-web test
      pnpm --dir pixflow-web build

- On Windows, Testcontainers verification may require the `-Pwindows-docker-tcp` Maven profile.
- Read [docs/development/linting.md](docs/development/linting.md) for authoritative lint commands, suppression rules, audit commands, and static-analysis expectations.

## Working Requirements

1. Before starting a task, first read the current execution plans in `docs/design-docs/exec-plans/`.
2. Based on the task scope, use [docs/design-docs/index.md](docs/design-docs/index.md) to identify and read the relevant design documents and references.
3. Code implementation must follow the existing design documents and execution plans. If a requested change conflicts with the documentation, explain the conflict before proceeding and confirm the handling approach.
4. Because this project is still in the development stage, keep changes clearly scoped and avoid introducing refactors or features unrelated to the current plan.
5. When the user asks for a plan, write it by referring to [PLANS.md](PLANS.md) unless the user explicitly asks not to use that format for a lightweight plan.

## Agent skills

### Issue tracker

Issues and specs are tracked as local Markdown files under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

The canonical triage labels are `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, and `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

This is a multi-context repository. Before changing a domain module, use [CONTEXT-MAP.md](CONTEXT-MAP.md) to locate and read its `CONTEXT.md`. System-wide architectural decisions live in `docs/adr/`. See `docs/agents/domain.md` for the domain documentation workflow.

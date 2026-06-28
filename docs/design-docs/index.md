# Design Documents Index

This directory contains the production-grade design documents for PixFlow. Use this index to decide which design files to read before planning, reviewing, or changing the project.

## Root Documents

- `design.md`: The overall PixFlow system design. It defines the target architecture, major runtime layers, technology choices, module boundaries, data model, asynchronous execution flow, and known risks.
- `index.md`: This index. It explains what belongs in each design-document folder.

## Folder Structure

- `base/`: Lowest-level shared design documents. This folder is for foundational, cross-project modules such as `common` and `contracts`. These designs should stay independent of higher-level business, harness, infra, or agent modules.
- `infra/`: Infrastructure-layer module designs. This folder is for technical adapters and platform capabilities such as AI access, cache, image processing, MQ, permission infrastructure, object storage, third-party clients, and vector storage.
- `harness/`: Cross-cutting runtime service designs. This folder is for Agent runtime support concerns such as context management, lifecycle hooks, tool execution boundaries, session/state/evaluation support, and other horizontal runtime mechanisms.
- `module/`: Business module designs. This folder is for domain-facing PixFlow capabilities such as memory, file handling, commerce data, DAG execution, task orchestration, conversation, vision, image generation, and rubrics.
- `exec-plans/`: Current execution plans. Files directly under this folder describe active or pending implementation plans and should be read before starting development work.
- `exec-plans/completed/`: Completed execution plans. These documents are historical records and implementation references; they are not the active plan unless an active plan explicitly points to them.

## Reading Guidance

Before starting development work, read the active plans in `exec-plans/` first. Then read `design.md` and the specific folder documents that match the scope of the change.

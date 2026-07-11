# Design Documents Index

This directory contains the production-grade design documents for PixFlow. Use this index to decide which design files to read before planning, reviewing, or changing the project.

## Root Documents

- `design.md`: The overall PixFlow system design. It defines the target architecture, major runtime layers, technology choices, module boundaries, data model, asynchronous execution flow, and known risks.
- `frontend/api.md`: 当前面向前端的 REST、SSE、WebSocket/STOMP 接口摘要。根级旧 `api.md` 已删除，不应恢复为权威来源；详细语义同时以对应 module/harness 设计为准。
- `web.md`: Vue 3 前端设计权威文档。覆盖架构、状态机、协议、Pinia 边界、视觉系统（浅色主题）、整体布局、组件库（Tailwind + radix-vue + 自绘）、跨模块联动。**视觉与布局、组件库选型以本文为准**。
- `index.md`: This index. It explains what belongs in each design-document folder.

## Folder Structure

- `base/`: Lowest-level shared design documents. This folder is for foundational, cross-project modules such as `common` and `contracts`. These designs should stay independent of higher-level business, harness, infra, or agent modules.
- `infra/`: Infrastructure-layer module designs. This folder is for technical adapters and platform capabilities such as authentication, AI access, cache, image processing, MQ, permission infrastructure, object storage, third-party clients, and vector storage.
- `harness/`: Cross-cutting runtime service designs. This folder is for Agent runtime support concerns such as context management, lifecycle hooks, tool execution boundaries, session/state/evaluation support, and other horizontal runtime mechanisms.
- `module/`: Business module designs. This folder is for domain-facing PixFlow capabilities such as memory, file handling, commerce data, DAG execution, task orchestration, conversation, vision, image generation, and rubrics. See subfolders: `memory.md`, `file.md`, `commerce.md`, `dag.md`, `task.md`, `conversation.md`, `vision.md`, `imagegen.md`, `rubrics.md`.
- `agent/`: Agent decision-layer assembly design (the `agent/` Maven module per `design.md §12`). Covers dynamic prompt assembly with section cache, skill mechanism, automatic memory recall (RRF), session memory accumulation, subagent runner, and the `SummarizationPort` / `SessionMemoryPort` SPI implementations. See `agent.md`.
- `frontend/`: Frontend module design summaries for `pixflow-web`. This folder splits the Vue 3 frontend into maintainable design documents for shell/routing/auth, transport/API, stores/runtime, chat, files, tasks, upload, and UI/visual modules. `web.md` remains the frontend authority; this folder documents module-level boundaries and implementation summaries.
- `exec-plans/`: Current execution plans. Files directly under this folder describe active or pending implementation plans and should be read before starting development work.
- `exec-plans/completed/`: Completed execution plans. These documents are historical records and implementation references; they are not the active plan unless an active plan explicitly points to them.

## Frontend Plans

- `exec-plans/completed/web-frontend-refactor-plan.md`: 已实施。PixFlow Web 视觉与组件库已从旧视觉库迁移到 Tailwind v3 + radix-vue + 自绘视觉层 + 自绘 SVG 图标；R7 清理与自动验证完成于 2026-07-01。
- `frontend/README.md`: 前端模块设计索引。按 Shell/路由/鉴权、传输与 API、Store 与 Runtime、Chat、Files、Tasks、Upload、UI 视觉系统拆分 `pixflow-web` 的模块设计摘要。

## Reading Guidance

Before starting development work, read the active plans in `exec-plans/` first. Then read `design.md` and the specific folder documents that match the scope of the change.

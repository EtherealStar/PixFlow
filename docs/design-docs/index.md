# Design Documents Index

This directory contains the production-grade design documents for PixFlow. Use this index to decide which design files to read before planning, reviewing, or changing the project.

## Root Documents

- `design.md`: The overall PixFlow system design. It defines the target architecture, major runtime layers, technology choices, module boundaries, data model, asynchronous execution flow, and known risks.
- `frontend/api.md`: 当前面向前端的 REST、SSE、WebSocket/STOMP 接口摘要。根级旧 `api.md` 已删除，不应恢复为权威来源；详细语义同时以对应 module/harness 设计为准。
- `frontend/README.md`: Vue 3 前端设计索引。`frontend/product.md`、模块文档与 `frontend/api.md` 共同构成前端权威规范。
- `web.md`: 旧的前端总文档兼容入口；不再承载独立状态机或接口定义，内容以 `frontend/` 为准。
- `index.md`: This index. It explains what belongs in each design-document folder.

## Folder Structure

- `base/`: Lowest-level shared design documents. `asset-references.md` is the canonical PACKAGE/SKU/IMAGE identity and expansion contract; `contracts.md` records the remaining cross-module shapes. These designs stay independent of higher-level business, harness, infra, or agent modules.
- `infra/`: Infrastructure-layer module designs. This folder is for technical adapters and platform capabilities such as authentication, AI access, cache, image processing, MQ, permission infrastructure, object storage, third-party clients, and vector storage.
- `harness/`: Cross-cutting runtime service designs. This folder is for Agent runtime support concerns such as context management, lifecycle hooks, tool execution boundaries, session/state/evaluation support, and other horizontal runtime mechanisms.
- `module/`: Business module designs. This folder is for domain-facing PixFlow capabilities such as memory, file handling, commerce data, DAG execution, task orchestration, conversation, vision, image generation, and rubrics. See subfolders: `memory.md`, `file.md`, `commerce.md`, `dag.md`, `task.md`, `conversation.md`, `vision.md`, `imagegen.md`, `rubrics.md`.
- `agent/`: Agent decision-layer assembly design (the `agent/` Maven module per `design.md §12`). Covers dynamic prompt assembly with section cache, skill mechanism, automatic memory recall (RRF), session memory accumulation, subagent runner, and the `SummarizationPort` / `SessionMemoryPort` SPI implementations. See `agent.md`.
- `frontend/`: PixFlow Web 的权威目标设计。该目录定义产品行为、前端状态、交互和面向前端的 HTTP/SSE/活动事件契约；不记录执行进度或历史缺陷审计。
- `exec-plans/`: Current execution plans. Files directly under this folder describe active or pending implementation plans and should be read before starting development work.
- `exec-plans/completed/`: Completed execution plans. These documents are historical records and implementation references; they are not the active plan unless an active plan explicitly points to them.

## Frontend Plans

- `exec-plans/completed/web-frontend-refactor-plan.md`: 已实施。PixFlow Web 视觉与组件库已从旧视觉库迁移到 Tailwind v3 + radix-vue + 自绘视觉层 + 自绘 SVG 图标；R7 清理与自动验证完成于 2026-07-01。
- `frontend/README.md`: 前端模块设计索引。按 Shell/路由/鉴权、传输与 API、Store 与 Runtime、Chat、Files、Tasks、Upload、UI 视觉系统拆分 `pixflow-web` 的模块设计摘要。

## Reading Guidance

Before starting development work, read the active plans in `exec-plans/` first. Then read `design.md` and the specific folder documents that match the scope of the change.

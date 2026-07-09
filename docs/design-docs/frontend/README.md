# PixFlow 前端模块设计索引

本文档夹承接 `docs/design-docs/web.md` 中的前端总设计，把 `pixflow-web` 的各个前端模块拆成可维护的模块设计摘要。`web.md` 仍是 Vue 3 前端的架构、视觉、状态机、协议与不变量权威；本目录用于按模块说明现有实现边界、数据流、关键文件和后续修改约束。

## 文档地图

| 文档 | 覆盖模块 | 对应实现 |
|---|---|---|
| `shell-routing-auth.md` | 应用入口、路由、鉴权守卫、三栏布局、网络与 traceId 浮层 | `src/main.ts`、`src/App.vue`、`src/router/`、`src/components/layout/`、`src/stores/ui.ts` |
| `transport-api.md` | HTTP client、SSE、STOMP/WebSocket、API adapter 边界 | `src/api/`、`src/transport/`、`src/types/api.ts` |
| `stores-runtime.md` | Pinia 状态层、Agent 回合状态机、任务运行时、上传状态机 | `src/stores/`、`src/runtime/`、`src/upload/` |
| `chat.md` | 会话页、消息流、Composer、@ 提及、HITL 提案确认 | `src/pages/ChatPage.vue`、`src/components/chat/`、`src/runtime/useAgentTurn.ts` |
| `files.md` | 素材/产物文件浏览、图片网格、批量删除、批量下载 | `src/pages/FilesPage.vue`、`src/components/files/`、`src/api/packages.ts`、`src/api/tasks.ts`、`src/api/downloads.ts` |
| `tasks.md` | 任务详情、任务面板、进度订阅、取消、结果预览下载 | `src/pages/TaskDetailPage.vue`、`src/components/tasks/`、`src/runtime/useTask.ts` |
| `upload.md` | 素材包分片上传、断点续传、worker 池、去重与取消 | `src/upload/`、`src/components/upload/`、`src/api/packages.ts` |
| `ui-visual.md` | Tailwind token、自绘 UI 原子、radix-vue 包装、自绘 SVG 图标 | `tailwind.config.ts`、`src/styles/`、`src/components/ui/`、`src/components/icons/` |

## 全局不变量

1. `web.md` 与 `api.md` 是前端设计和后端协议的上位权威；本目录不得发明与二者冲突的新协议或视觉规则。
2. `api/` 只封装端点调用，不 import Vue 组件；`transport/` 不知道业务域。
3. `runtime/` 承载长流程状态机；流式 token、上传分片、WS 订阅不能散落在页面组件的临时 Promise 链里。
4. Pinia 只保存前端需要共享的状态副本，不把后端列表响应无差别持久化。
5. 文件字节、`confirmationToken`、预签名 URL 不进入 console 日志；traceId 可以用于错误定位。
6. 产品界面只使用浅色主题、Tailwind design token、自绘 UI 层和 SVG 图标；禁止恢复旧视觉库或 emoji。

## 修改入口建议

- 修改布局、路由、登录态和面板交互时，先读 `shell-routing-auth.md`。
- 修改请求协议、错误归一化、SSE 或 WS 时，先读 `transport-api.md` 并同步检查 `api.md`。
- 修改上传、任务进度或 Agent 回合时，先读 `stores-runtime.md` 和对应业务文档。
- 修改视觉组件、颜色、圆角、阴影或图标时，先读 `ui-visual.md`，再对照 `web.md` §五到 §十三。

# PixFlow 前端设计

> 面向 Vue 3 前端的架构与机制设计。范围：单用户单机的对话 / 任务 / 素材包 / 评分前端。
> 配套阅读：[`design.md`](design.md) 总架构、`api.md` 前端 API 契约、`module/file.md` 上传与解压落地、`module/conversation.md` 对话会话模型。
> 本文不写关键实现代码，只写**机制、状态机、协议、不变量与边界**。伪代码用于说明意图。

---

## 目录

- [PixFlow 前端设计](#pixflow-前端设计)
  - [目录](#目录)
  - [一、文档定位与设计原则](#一文档定位与设计原则)
  - [二、运行环境与目标](#二运行环境与目标)
  - [三、技术栈选型](#三技术栈选型)
  - [四、目录结构](#四目录结构)
  - [五、传输层](#五传输层)
    - [5.1 HTTP 客户端](#51-http-客户端)
    - [5.2 SSE 客户端](#52-sse-客户端)
    - [5.3 STOMP 客户端](#53-stomp-客户端)
  - [六、令牌桶限流（前端）](#六令牌桶限流前端)
  - [七、分片上传（核心）](#七分片上传核心)
    - [7.1 协议总览](#71-协议总览)
    - [7.2 整包去重](#72-整包去重)
    - [7.3 续传定位（uploadId 持久化）](#73-续传定位uploadid-持久化)
    - [7.4 分片状态机](#74-分片状态机)
    - [7.5 并发与限流](#75-并发与限流)
    - [7.6 失败重试](#76-失败重试)
    - [7.7 取消与清理](#77-取消与清理)
    - [7.8 与后端去重/锁的协作边界](#78-与后端去重锁的协作边界)
  - [八、Agent 回合运行时](#八agent-回合运行时)
  - [九、任务运行时](#九任务运行时)
  - [十、状态管理（Pinia）](#十状态管理pinia)
  - [十一、路由与页面](#十一路由与页面)
  - [十二、HITL 流程的 UI 编排](#十二hitl-流程的-ui-编排)
  - [十三、错误处理与 traceId](#十三错误处理与-traceid)
  - [十四、决策记录（V1）](#十四决策记录v1)
  - [十五、修订记录](#十五修订记录)

---

## 一、文档定位与设计原则

`pixflow-web` 是 PixFlow 的唯一前端交付物。本文定义其**架构、状态机、协议与不变量**，所有后续实现必须以此为准；与本文冲突的实现需先回溯修订本文。

模块专属设计原则：

1. **后端契约权威**。所有 HTTP / SSE / WS 端点、错误码、状态机均以 `api.md` 为准；前端不重新发明协议，不私自约定"前端字段"。
2. **客户端状态机显式**。Agent 回合、任务、分片上传都是有状态流程，**必须**抽出 composable / class 形式的有限状态机，不允许散落在组件的 `setTimeout` / `Promise.then` 里。
3. **断点续传与可恢复性是默认要求**。长耗时操作（分片上传、Agent 回合、任务进度）刷新、断网、杀进程后必须能恢复，不要求用户重头再来。
4. **失败自动重试，不打扰用户**。网络抖动、临时 5xx、令牌桶 429 全部由客户端 worker 自动退避重试；只在"重试耗尽"或"业务不可恢复错误"时打扰用户。
5. **去重与锁是后端职责**。前端不引入 Redisson 客户端；前端只通过"已传分片列表 / fileHash 命中"与"429 退避"配合后端实现幂等。
6. **限流是双向的**。前端先做令牌桶预限流（保护本地与对后端的请求节奏），后端再做最终令牌桶限流（防滥用）。前端令牌桶**不是**安全机制，是**协作流控**。
7. **不写关键代码**。本文记录机制、协议、状态机、不变量；具体 TS 实现放在 `pixflow-web/src/`，**不在设计文档里堆代码**。

---

## 二、运行环境与目标

- **单用户、单进程、单浏览器**。无登录、无 i18n（中文优先）、无移动端适配。
- **同机部署**：后端 Spring Boot + 前端 Vite dev server（或 Nginx 静态产物），浏览器与后端通过 `localhost` 通信。
- **可观测基线**：traceId 在 HTTP / SSE / WS 全链路透传；前端右下角常驻 traceId 浮窗。
- **韧性基线**：刷新 / 断网 / 杀进程后长耗时操作可恢复（具体见各运行时状态机）。

---

## 三、技术栈选型

| 维度 | 选型 | 理由 |
|---|---|---|
| 框架 | Vue 3 + `<script setup>` + TypeScript 严格模式 | 与 `design.md` 一致 |
| 构建 | Vite 6 | 启动快、热更优 |
| 包管理 | pnpm | 单仓单包即可 |
| 路由 | vue-router 4 | 标配 |
| 状态 | Pinia | 多 store 按域切 |
| UI 库 | Element Plus | 后端设计已选；按需引入 |
| HTTP | 原生 `fetch` + 自封装 client | 不上 axios；统一注入 Idempotency-Key / traceId / 错误归一化 |
| SSE | `fetch` + `ReadableStream` 解析 | 绕过 `EventSource` 不能自定义 header 的限制 |
| WebSocket | `@stomp/stompjs` | 后端用 STOMP 协议 |
| 校验 | Zod | SSE 事件 / 表单 / API 响应 schema |
| 哈希 | Web Crypto `SubtleCrypto.digest('SHA-256', ...)` | 零依赖、性能好；**MD5 不被浏览器原生支持**，统一用 SHA-256 |
| 测试 | Vitest | 关键 runtime 状态机单测 |

**否决项**（及理由）：
- ❌ **Uppy / tus-js-client**：自写分片上传，状态机更可控，且要支持同 fileHash 整包去重。
- ❌ **axios / ofetch**：自封装 `fetch` 已经够薄。
- ❌ **TanStack Query**：单用户单机，手写缓存层即可，引入额外依赖收益不抵复杂度。
- ❌ **Pinia Plugin Persistedstate（持久化插件）**：上传会话需要更细粒度控制（key、TTL、清理），手写 `upload-session-store`。
- ❌ **Monorepo（pnpm workspace）**：单端交付物，单仓单包足够。
- ❌ **Storybook / PWA / Sentry / UnoCSS**：本期不引入。

---

## 四、目录结构

```
pixflow-web/
├── index.html
├── vite.config.ts
├── package.json
├── tsconfig.json
└── src/
    ├── main.ts
    ├── App.vue
    ├── router/
    ├── api/                  # 后端端点调用层（薄）
    │   ├── client.ts         # fetch 封装：Idempotency-Key、traceId、错误归一化
    │   ├── conversations.ts
    │   ├── messages.ts
    │   ├── attachments.ts
    │   ├── confirm.ts
    │   ├── tasks.ts
    │   ├── packages.ts
    │   └── rubrics.ts
    ├── transport/
    │   ├── sse.ts            # SSE 通用连接
    │   └── ws.ts             # STOMP 通用连接 + 重连 + 重订阅
    ├── upload/
    │   ├── chunker.ts        # 文件分片（5MB）
    │   ├── hasher.ts         # SHA-256（Web Crypto）
    │   ├── sessionStore.ts   # uploadId 持久化（localStorage）
    │   ├── uploadJob.ts      # 单文件上传状态机
    │   └── chunkWorker.ts    # 分片 worker（并发 + 重试 + 限流）
    ├── runtime/
    │   ├── useAgentTurn.ts
    │   ├── useTask.ts
    │   └── usePackageProgress.ts
    ├── stores/
    │   ├── conversations.ts
    │   ├── agentTurns.ts
    │   ├── tasks.ts
    │   ├── packages.ts
    │   ├── uploadJobs.ts
    │   ├── rubrics.ts
    │   └── ui.ts
    ├── pages/
    │   ├── ChatPage.vue
    │   ├── TasksPage.vue
    │   ├── PackagesPage.vue
    │   ├── RubricsPage.vue
    │   └── SettingsPage.vue
    ├── components/
    │   ├── ProposalCard.vue
    │   ├── ChallengeDialog.vue
    │   ├── TaskProgressCard.vue
    │   ├── PackageUploader.vue
    │   ├── PackageExtractionProgress.vue
    │   ├── ResultPreview.vue
    │   └── MessageStream.vue
    ├── utils/
    │   ├── id.ts             # crypto.randomUUID
    │   ├── format.ts
    │   └── error.ts
    └── types/
        ├── api.ts
        ├── agent.ts
        └── upload.ts
```

**分层约束**：
- `api/` 只调 `transport/` 和 `utils/`，不直接 import 组件。
- `transport/` 不知道任何业务域。
- `runtime/` 是 composable，不依赖 Pinia（除非要订阅 store 状态）。
- `stores/` 不依赖 `api/`（单向数据流）。
- `pages/` 组合 `runtime/` + `stores/` + `components/`。

---

## 五、传输层

### 5.1 HTTP 客户端

`api/client.ts` 统一处理：

- **traceId 注入**：每个请求 header 注入 `X-Trace-Id`（生成或透传），响应里回读（覆盖入参用于关联 Sentry breadcrumb）。
- **Idempotency-Key 注入**：`POST /confirm/.../submit` 与其他业务非幂等端点由调用方传 key，client 不自动生成（语义不可猜）。
- **错误归一化**：HTTP 4xx/5xx body 按 `base/common.md` 的 envelope 解析为 `ApiError { status, errorCode, message, traceId, details? }`，**不抛原生 `Error`**。
- **有限重试**：仅对 `GET` 在网络错 / 5xx 时自动重试 1 次（指数退避 500ms），其余方法由调用方决策。
- **不代理 SSE 字节**：SSE 通过专用 `transport/sse.ts` 走原始 `fetch` 流。

### 5.2 SSE 客户端

`transport/sse.ts` 行为：

- 用 `fetch` + `ReadableStream` 读 `text/event-stream`，**不**用浏览器原生 `EventSource`（后者不能自定义 header）。
- 解析 `event:` / `data:` / 空行分隔，注释行（`:` 开头）识别为 heartbeat，30s 无数据视为真断。
- 每个事件按 `api.md` 的事件名（`assistant_delta` / `tool_call_ready` / ...）派发到 handlers；事件 schema 用 Zod 校验，**校验失败记 trace breadcrumb 并触发 onError**。
- 断流语义：V1 **不**做 SSE 续传（`Last-Event-ID` 后端不支持），断流即视为回合失败，UI 提示用户重发。
- `AbortController` 透传：用户点"停止生成"立即中断读取。
- 浏览器 `visibilitychange`：切走 Tab 时不主动关闭 SSE（回合同步进行），切回时不 flush（流式渲染本就低帧率）。

### 5.3 STOMP 客户端

`transport/ws.ts` 行为：

- 用 `@stomp/stompjs` 连 `WS /ws/progress`，CONNECT 头预留 `X-Auth-Token`（V1 单机无用户系统，留空）。
- **重连退避**：1s → 2s → 4s → 8s → 30s（封顶），由 STOMP 客户端的 `reconnectDelay` 配合自定义退避表实现。
- **重订阅**：重连成功后**重放**之前订阅过的所有 destination（任务面板里所有在看的 taskId 重新订阅）。
- **服务端断 vs 客户端断**：STOMP `disconnect` 由前端主动调用，浏览器收不到 `close` 事件不视为错。
- **断线补偿**：重连成功后，对每个 taskId 调一次 `GET /api/tasks/{id}` 拉最新状态，与 WS 推送按 `(taskId, updatedAt)` 取较大者。
- **不持久化 WS 订阅**：刷新页面后从 Pinia `useTasksStore` 重建订阅集合。

---

## 六、客户端协作流控

后端已落地完整令牌桶（见 `api.md` §`去重 / 锁 / 限流总览`），客户端**不再**实现完整令牌桶；只暴露一个轻量"全局并发保护"机制，仅作协作流控（防误用 / 退避示意），不做安全机制。

**核心约束**：
- 单用户单机场景下，`upload-chunk` 桶（cap=rps=并发数）恒为满，是零作用伪限流——已删除。
- 分片上传由 worker 池天然限流（默认并发数 = 4），与令牌桶叠加是过度设计。
- 客户端仅保留一个**全局 in-flight 信号量**：限制同时在飞的 HTTP 请求数（默认 6）。

**使用场景**：
- `api` 信号量：所有非上传 HTTP 请求受全局 in-flight 上限保护（`acquire()` → 请求 → `release()`），超限请求**入队等号**（不 sleep）。
- 上传分片 PUT：直接走 worker 池并发数限制（默认 4），不再过令牌桶。
- 后端返 `429 + Retry-After`：调用方按 `Retry-After` 退避，与客户端 in-flight 信号量独立。

**不变量**：
- 客户端协作流控**不**替代后端令牌桶；二者**叠加**：客户端保护本机节奏，后端兜底防滥用。
- 协作流控**不**是安全机制（用户可以改前端代码）。
- 客户端**不**对上传分片 PUT 单独做退避节流（worker 池就是节流）；后端 429 通过响应 `Retry-After` 头直接告知退避时长。

---

## 七、分片上传（核心）

### 7.1 协议总览

| 阶段 | 前端 | 后端 | 备注 |
|---|---|---|---|
| 1. 算整包 hash | **切片累加 SHA-256**（4MB 块 → `subtle.digest` → hex 列表指纹 + 客户端传完整字节给服务端做二次校验） | — | Web Crypto 单次 digest 上限约 4GB，分块 4MB 内存峰值稳定 |
| 2. 调 init | 传 `{filename, size, fileHash, chunkSize}` | 同 fileHash 互斥锁 + 整包去重查 | 见 §7.2 |
| 3. 算分片 + 过滤 | `chunkFile(file, chunkSize)` 取全集；用 `uploadedChunks` 差集过滤 | — | 断点续传关键 |
| 4. 并发传分片 | `PUT /chunks/{index}`（带分片 hash） | 分片锁 + 幂等写 | 见 §7.8 |
| 5. 调 complete | 传整包 hash（可选但推荐） | 完整校验 + MinIO 拼接 + 落 `asset_package` | 见 §7.8 |
| 6. 订阅解压进度 | 订阅 `/topic/packages/{packageId}/progress` | `ProgressNotifier` 推帧 | 与上传解耦 |

**分片大小**：固定 5MB（`5242880` 字节）。前端按此切片，后端按此校验 chunk size 头。

**分片并发数**：默认 4。可配置（`pixflow.web.upload.concurrency`）。worker 池大小本身就是并发上限，无需令牌桶叠加。

### 7.2 整包去重

整包去重 = "同 fileHash 不重复解压"。

- 前端在 `init` 阶段把整包 hash 作为必传字段。
- 后端在 `init` 处理中：
  1. 取 `Redisson 锁 lock:package-upload:{fileHash}`（**看门狗续期**，TTL 与 `session.ttl` 对齐），检查 `asset_package.file_hash` 唯一索引：
     - **命中 READY/PARTIAL** → 返 `{mode: "DEDUP", packageId, status}`；前端**不再传任何分片**。
     - **未命中** → 创建 `package_upload_session`，返 `{mode: "UPLOAD", uploadId, ...}`。
  2. **同 fileHash 正在被另一客户端上传**（DB 中有 `status=UPLOADING` 的 session） → 返 `{mode: "DEDUP", status: "UPLOADING", uploadId: existingId, uploadedChunks: [...]}`，前端可接管续传或提示冲突。
- 前端对 `mode=DEDUP` 的处理：
  - `status=READY` → 提示"该压缩包已存在"，不进入上传流程，跳到对应 `packageId` 详情页。
  - `status=UPLOADING` → 提示"另一上传正在进行"，由用户选择"接管续传"或"放弃"。

**关键不变量**：
- **同 fileHash 互斥锁是后端责任**；前端**不**实现分布式锁。
- 前端在 `init` 返回 `DEDUP/READY` 时**不会**写任何 MinIO / Redis 字节。
- `asset_package.file_hash` 唯一索引是**最终防线**；即便锁失效，DB 唯一约束兜底。

### 7.3 续传定位（uploadId 持久化）

刷新 / 断网 / 杀进程后恢复 = "用 `fileHash` 找回 `uploadId`"。

**本地存储**（`upload/sessionStore.ts`）：
- key：`pixflow.upload.session.{fileHash}`
- value：`{ uploadId, filename, size, chunkSize, savedAt }`
- TTL：7 天（按 `savedAt` 过期；过期后调 init 会拿到新 `uploadId`）。
- 多标签页：通过 `localStorage` + `storage` 事件（V1 简化：不监听，依赖后端 `uploadedChunks` 列表自然去重）。

**恢复流程**（`uploadJob.run()` 入口）：
1. 读 `sessionStore.get(fileHash)`：若存在且未过期，把 `uploadId` 暂存为"恢复候选"。
2. 调 `POST /init`（不传 `uploadId`，让后端按 `fileHash` 决策）：
   - 返 `mode=UPLOAD` 且 `uploadId` 等于"恢复候选" → 走 `GET /sessions/{uploadId}` 拉 `uploadedChunks` 列表，差集上传。
   - 返 `mode=UPLOAD` 且 `uploadId` 不等于"恢复候选" → 视为"上一会话被后端回收（EXPIRED/CANCELLED）"，覆盖本地存储，走全新上传。
   - 返 `mode=DEDUP/READY` → 跳过上传。
3. 上传过程中持续更新 `sessionStore`（`uploadId` 持久化到分片 PUT 成功至少一次后）。
4. `complete` 成功后 `sessionStore.delete(fileHash)`（避免误命中陈旧会话）。

### 7.4 分片状态机

`uploadJob.ts` 的状态机（**整文件**粒度）：

```
idle
  → hashing            (算整包 hash)
  → initing            (调 /init)
  → uploading          (并发传分片)
       │   ↑↓
       │   retry (worker 内重试，详见 §7.6)
       ↓
  → completing         (调 /complete)
  → done
  → error              (重试耗尽 / 业务不可恢复)
  → cancelled          (用户主动取消)
```

分片粒度状态（在 `chunkWorker` 内部）：

```
PENDING → UPLOADING → UPLOADED
                  ↘
                   FAILED_RETRYING (退避中) → UPLOADING
                                          ↘
                                           FAILED (重试耗尽，整文件 → error)
```

**不变量**：
- 同一 `index` 同一时刻最多一个 worker 在写。
- `UPLOADED` 集合与后端 `uploadedChunks` 集合**在每个 PUT 成功后**用响应里的 `uploadedChunks` 字段校准（应对其他客户端的并发上传）。
- 整文件 `error` 时**保留**已上传分片（不主动清理），用户可重试"失败分片"或整文件；**用户点取消才 DELETE**。
- 整文件 `cancelled` 时 DELETE 会话 + 清本地 `sessionStore`。

### 7.5 并发与限流

**并发模型**：
- 固定大小 worker 池（默认 4）。worker 从共享队列取分片，空闲 worker 立即取下一个。
- **不**用 `Promise.all(chunks.map(upload))`：避免一次性占用所有网络连接。

**限流**：
- worker 池大小固定为并发上限（默认 4），无令牌桶。
- 后端返 `429 + Retry-After`：调用方按 `Retry-After` 退避后**重试**（不放回队列；保证顺序处理）。
- 退避上限：单分片最多重试 5 次（默认），单分片累计退避超过 60s 视为"长期拥塞" → 标记 `FAILED`，整文件 → `error`。

### 7.6 失败重试

**worker 内自动重试**（不暴露给用户）：
- 触发条件：网络错、5xx、429、`UPLOAD_SESSION_NOT_UPLOADING`（409，会话已被并发取消）。
- 退避：1s → 2s → 4s → 8s → 16s → 30s（封顶）；同一分片最多 5 次。
- **不**自动重试的：`CHUNK_HASH_MISMATCH`（4xx，客户端 bug，需用户重传整个文件）、`INCOMPLETE_CHUNKS`（complete 阶段才暴露，分片层面不会触发）。
- `ALREADY_EXISTS`（200，幂等命中）**不是错误**，记入 `uploadedChunks` 后跳过。

**整文件级失败**：
- 任一分片重试耗尽 → 整文件进入 `error` 状态，**不**自动重试整文件（避免死循环）。
- UI 显示"上传文件失败，请重试"按钮 → 用户点"重试" → 重置失败分片状态 → 重新入队（worker 自动重试机制复用）。

### 7.7 取消与清理

**用户主动取消**：
- 调 `AbortController.abort()` 中断所有在飞 PUT。
- 调 `DELETE /api/files/packages/sessions/{uploadId}` 通知后端清理 Redis 元数据 + MinIO 临时分片。
- 清本地 `sessionStore`。
- UI 立即进入 `cancelled` 态（不等待 DELETE 响应）。

**后端取消的传播**：
- 另一客户端 DELETE 同一 `uploadId` → 当前 worker 的下一次 PUT 返 `409 UPLOAD_SESSION_NOT_UPLOADING` → 整文件 → `cancelled`。
- 提示用户"该上传已被取消"。

**超时清理**（后端职责，前端不感知）：
- `package_upload_session` 设 TTL（默认 24h），过期后 `init` 拿到新 `uploadId`，本地 `sessionStore` 自动覆盖。

### 7.8 与后端去重/锁的协作边界

| 维度 | 前端责任 | 后端责任 |
|---|---|---|
| **整包 hash 计算** | 流式 SHA-256 | — |
| **整包去重查询** | — | `asset_package.file_hash` 唯一索引 |
| **同 fileHash 互斥** | — | `Redisson 锁 lock:package-upload:{fileHash}`（看门狗续期） |
| **分片切分** | 5MB 切片 | chunk size 校验 |
| **分片 hash 计算** | `crypto.subtle.digest('SHA-256', chunkBlob)` | 分片 hash 校验 |
| **分片去重** | 读 `uploadedChunks` 过滤 | `chunk:{uploadId}:{index}` Redis 元数据 + MinIO 单写语义 |
| **分片并发写保护** | 客户端并发控制（worker 池） | `Redisson 锁 lock:package-chunk:{uploadId}:{index}`（短时锁） |
| **complete 校验** | 传整包 hash | 分片总数校验 + MinIO `composeObject` 拼接 + hash 校验 |
| **complete 防并发** | — | `Redisson 锁 lock:package-complete:{uploadId}`（短时锁） |
| **cancel 防并发清理** | 调 DELETE | `Redisson 锁 lock:package-cancel:{uploadId}`（短时锁） |
| **速率限制** | 客户端令牌桶 | 服务端令牌桶（按 `uploadId` + IP 二级），429 + `Retry-After` |

**关键不变量**：
- 前端**不**引入 Redisson 客户端；**所有**分布式锁在后端。
- 前端**不**假设"上传一定成功"；每个 PUT 响应都用于校准 `uploadedChunks`（应对并发场景）。
- 前端**不**在本地缓存分片字节（不写 IndexedDB）；断网重传靠后端 `uploadedChunks` + 重新 `slice` 源文件。

---

## 八、Agent 回合运行时

`runtime/useAgentTurn.ts`（composable）状态机：

```
idle
  → sending              (POST /messages)
  → streaming            (SSE connected, receiving deltas)
       ├─ tool_call_ready → tool_started → tool_result 循环
       ├─ challenge       (HITL 二次确认) → awaiting_challenge
       ├─ proposal + no challenge → awaiting_confirm
       └─ completed → idle
  → awaiting_challenge   (用户回答) → re-challenge → token → awaiting_confirm
  → awaiting_confirm     (用户确认) → submitting → taskId → idle
  → error                (SSE 断流 / 业务不可恢复)
  → cancelled            (用户点停止)
```

**关键不变量**：
- **流式 token 不进 Pinia**：`deltas` 维护在 composable 内的 `ref<string>`，直接喂 `<MessageStream>` 组件。Pinia 只存"回合摘要状态"（`phase`、`proposal`、`taskId`）。
- **confirmationToken 不出现在 console / 日志 / 任何调试输出**。
- **`Idempotency-Key` 在 `useAgentTurn.confirm()` 内生成并缓存**到 `sessionStorage`（key = proposalId，TTL 30min），刷新后能复用同一 key 避免重复扣款。
- **SSE 断流语义**：V1 不做续传，断流即 `error` 状态，提示用户重发同 prompt（前端可把 prompt 缓存到 localStorage 让用户一键重发）。
- **用户点拒绝** → 关闭 proposal，**不**调用 `/submit`；前端把"已拒绝"作为本地 USER 消息塞回合（让 LLM 知道用户拒绝），**不**调后端 `/messages`（避免污染远端 transcript）。
- **多回合并发**：同 conversationId 不允许两个 active 回合；新回合触发时若已有 active，先 abort 前一回合。

**与 SSE 客户端的衔接**：
- `useAgentTurn` 持有一个 `AbortController`；abort 时关闭 SSE 连接。
- 浏览器 `visibilitychange` 不中断 SSE（回合同步进行）。

---

## 九、任务运行时

`runtime/useTask.ts`（composable）状态机：

```
queued → running → completed | failed | partial | cancelled
                    │
                    └─ (reconnect 触发) → 拉 GET 校准 → 回到 running/终态
```

**数据源**：
- **初始快照**：`GET /api/tasks/{id}`（拉 status + progress 一次性快照）。
- **实时更新**：WS 订阅 `/topic/task-progress/{cid}/{tid}`。
- **断线补偿**：WS 重连后**先**调 `GET` 拉一次最新，与本地 state 按 `updatedAt` 取较大者；**后**续接 WS 推送。

**取消语义**（对齐 `api.md`）：
- 用户调 `POST /conversations/{cid}/tasks/{tid}/cancel`。
- 前端**乐观更新**为 `cancelled`（按钮立即变灰）。
- WS 推送真 `cancelled` 信号时**幂等处理**（已在 `cancelled` 则不重复通知）。

**结果预览 / 下载**：
- `GET /api/tasks/{id}/results?page=` 拉结果列表。
- `GET /api/tasks/{id}/downloads?resultId=` 拿 presigned URL → **`window.open(url)`** 直接打开（**不代理字节**）。
- 单结果下载：`<img :src="presignedUrl" />`；批量下载：拼 `bundle` URL 后 `window.open`。

---

## 十、状态管理（Pinia）

| Store | 关键 state | 关键 action |
|---|---|---|
| `useConversationsStore` | `items`、`currentId` | `refresh()`、`select(id)` |
| `useAgentTurnsStore` | `Map<conversationId, AgentTurnState>` | `get(conversationId)`、`reset(conversationId)` |
| `useTasksStore` | `Map<taskId, TaskState>`、`watching: Set<taskId>` | `watch(taskId)`、`unwatch(taskId)` |
| `usePackagesStore` | `items`、`Map<packageId, PackageState>` | `refresh()`、`upsert(state)` |
| `useUploadJobsStore` | `Map<jobId, UploadJobState>` | `add(job)`、`update(jobId, patch)` |
| `useRubricsStore` | `templates`、`runs` | 占位（V1 不实现） |
| `useUIStore` | `sidebarOpen`、`floatingTraceId` | `toggleSidebar()` |

**反模式重申**：
- ❌ SSE 流式 `deltas` 进 Pinia → 失去响应式粒度，进 composable 的 `ref`。
- ❌ 后端列表数据塞 Pinia 重复缓存 → Pinia 只存**前端关心的状态副本**，原始数据从 fetch 即用。
- ❌ Pinia 持久化（整个 store）→ 仅 `upload/sessionStore` 手写精细持久化（key、TTL、清理策略可控）。

---

## 十一、路由与页面

```
/                          → 重定向到 /chat/:cid 或新建
/chat/:cid                 → 主聊天页（核心）
/chat/:cid/tasks/:tid      → 任务详情（右侧抽屉或子路由）
/packages                  → 素材包管理
/packages/:pid             → 素材包详情（含解压进度、错误明细）
/rubrics                   → 评分（V1 占位）
/settings                  → 偏好设置（V1 占位）
```

**布局**：
- 左侧固定 280px 会话栏（可折叠）。
- 中央主区。
- 右侧按需滑出"任务进度 / 结果预览 / 评分详情"抽屉（三态：关 / 半屏 / 全屏）。

---

## 十二、HITL 流程的 UI 编排

`<ProposalCard>` 渲染**待确认提案**（Agent 不直接执行，前端是守门人）。

**用户点"确认执行" → 前端编排**（按 `api.md` HITL 章节）：

1. **调用 `/challenge`**：`POST /api/conversations/{cid}/confirm/{proposalId}/challenge`。
   - 返 `needChallenge=false` → 拿 token 直接进步骤 3。
   - 返 `needChallenge=true` → 弹 `<ChallengeDialog>` 阻塞。
2. **Challenge 回答**：用户输入答案 → 调 `/challenge` 带 `answer` 拿 token（**该接口设计上是同接口复用**：V1 简单做法；也可拆 `/answer` 子路径，**待后端确认**）。
3. **调用 `/submit`**：`POST /api/conversations/{cid}/confirm/{proposalId}/submit`，自动注入 `Idempotency-Key`。
   - 返 `taskId` → 跳到 `/chat/{cid}/tasks/{taskId}`。
   - 错误码 `PROPOSAL_CHALLENGE_FAILED` (400) → 焦点回到 challenge 输入框。
   - 错误码 `PROPOSAL_CHALLENGE_EXPIRED` (410) → 提示"二次确认已过期，请重新生成方案"。
   - 错误码 `PROPOSAL_ALREADY_CONFIRMED` (409) → 提示"已确认，跳转到任务面板"。

**用户点"拒绝"**：
- 关闭 proposal，**不**调 `/submit`。
- 前端把"已拒绝该方案"作为本地 USER 消息**插入回合 transcript**（不调后端 `/messages`），让 LLM 在下一回合看到拒绝结果。
- Pinia 中 proposal 状态置为 `REJECTED`。

**安全护栏**（前端必须做的）：
- "确认执行"按钮按下后立即 disable（避免双击）。
- `Idempotency-Key` 与 `proposalId` 绑定缓存到 `sessionStorage`（TTL 30min）；网络重试时**不**重新生成 key。
- Sentry 等日志**不**上报 `confirmationToken`，**可**上报 `proposalId` + `taskId` 关联（V1 暂未上 Sentry，留注释）。

---

## 十三、错误处理与 traceId

**错误分类与 UI 表现**：

| 错误来源 | 示例错误码 | UI 表现 |
|---|---|---|
| 网络断 | `NETWORK_ERROR` | 顶部条幅"网络已断开，正在重连..." |
| SSE 断流 | `STREAM_INTERRUPTED` | 消息流下方"流中断，请重新发送" |
| WS 断线 | `WS_RECONNECTING` | 任务卡片小图标"连接中断（重连中...）" |
| 业务 4xx | `PROPOSAL_CHALLENGE_FAILED` 等 | 弹 Element Plus `ElMessage`，焦点回输入框 |
| 410 业务 | `PROPOSAL_CHALLENGE_EXPIRED` | 提示"已过期" |
| 5xx | `INTERNAL_*` | 通用错误 toast + traceId 复制按钮 |
| 上传 429 | `UPLOAD_RATE_LIMITED` | 上传卡片提示"被限流，自动重试中..." |
| 上传 400/409 | `CHUNK_HASH_MISMATCH` / `UPLOAD_SESSION_NOT_UPLOADING` | 上传卡片对应错误信息（不打断主流程） |

**traceId 浮窗**：
- 右下角常驻小窗显示当前会话的 traceId。
- 任何错误 toast 自带"复制 traceId"按钮。
- traceId 透传：HTTP 请求头 → 响应头回读 → SSE / WS 帧内携带。
- V1 暂未接 Sentry，traceId 仅用于**用户报障时人工检索**后端日志。

**关键不变量**：
- confirmationToken、用户上传文件字节、附件 presigned URL **不**出现在 console / 日志。
- proposalId / taskId / conversationId / traceId / packageId **可**出现在 console（仅调试模式，prod 关闭 console）。

---

## 十四、决策记录（V1）

仅保留跨章节决策；与正文重复项（如"前端固定 5MB"、"客户端令牌桶默认值"、"Idempotency-Key 缓存介质"）由正文单一来源覆盖。

| 范围 | 决策 | 落地方式 |
|---|---|---|
| `package_upload_session` 存储 | **Redis only**（hash + set 结构） | 不引入新表；崩溃后依赖 init 重新创建 + 已传分片列表续传（详见 `api.md` §`上传会话存储与生命周期`） |
| `Idempotency-Key` 介质 | **sessionStorage**，key = `pixflow.idemp.{proposalId}`，TTL 30min；**多标签页共享同一 proposalId 时复用同一 key** | `useAgentTurn` 内置；提案幂等语义要求"同 proposalId 多次调 `/submit` 等同一次" |
| 二次确认 challenge 路径 | **复用 `/challenge` 接口**：第一次无 body 拿 challenge，第二次带 `{answer}` 拿 token | `api.md` HITL 章节同款；前端 `useAgentTurn.confirm()` 编排 |
| 客户端协作流控策略 | 删除 `upload-chunk` 桶（worker 池天然限流）；保留 `api` 全局 in-flight 信号量（默认 6），仅作协作流控 | `pixflow.web.api.inflight` 配置项；不再有完整令牌桶 |
| SHA-256 切片累加 | 分块 4MB → `subtle.digest` → hex 列表指纹 + 客户端传完整字节给服务端做二次校验 | 替代单次 digest；4MB 块在 100MB~1GB 文件下内存峰值稳定 |
| `confirmationToken` 脱敏层 | dev 模式 `console` wrapper（剥掉含 `confirmationToken` 字段的对象），V1 无 Sentry | `vite.config.ts` 配 `define: { __DEV__: ... }`；`main.ts` 装 wrapper |

---

## 十五、修订记录

- **2026-07-01**：按 6 个用户决策重整本文：
  1. §六 令牌桶整章重写为"客户端协作流控"，删除 `upload-chunk` 桶（worker 池天然限流），`api` 桶简化为全局并发保护（默认 in-flight=6）；目录删除 `ratelimit/tokenBucket.ts`。
  2. §7.1 「1. 算整包 hash」段落重写，明确"切片累加 hash（分块 4MB）"；移除"V1 简化"标注。
  3. 原 §14「对 api.md 的细化」整章删除（api.md 已落地全部 5 条改动；保留会让双向同步成本高于收益）。
  4. 原 §15 决策记录瘦身：删除与正文重复的"前端固定 5MB / 客户端令牌桶默认值 / Idempotency-Key 缓存介质"；只保留跨章节决策（`package_upload_session` Redis-only / `Idempotency-Key` 介质 / 复用 `/challenge` / 客户端桶策略 / SHA-256 切片累加 / `confirmationToken` 脱敏层）。
  5. §4 目录结构调整：删除 `ratelimit/` 目录；占位内容（`pages/RubricsPage.vue` / `pages/SettingsPage.vue` / `useRubricsStore` / `/rubrics` 路由 / `/settings` 路由）标"Wave 6 / V1 不在范围"。
  6. 新增 §十五「修订记录」段，标注本次重整。

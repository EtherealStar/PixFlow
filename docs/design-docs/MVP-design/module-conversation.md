# module-conversation —— 对话与消息

> 源码：`src/main/java/com/etherealstar/pixflow/module/conversation/`

对话模块承担运营侧的「自然语言入口」：创建对话、查看列表、查看消息历史、发送消息并触发 DAG 解析。任务创建与执行属于 `module/task`，本模块只负责把「经校验的用户消息」持久化后交给上游 `DagParser`。

## 1. 目录结构

```
module/conversation/
├── controller/  ConversationController
├── service/     ConversationService
├── entity/      Conversation / Message
├── mapper/      ConversationMapper / MessageMapper（MyBatis Plus BaseMapper）
└── dto/         ConversationCreateResponse / ConversationListItem
                 MessageItem / SendMessageRequest / SendMessageResponse
```

## 2. 实体

### 2.1 `Conversation`（`conversation` 表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 主键，自增 |
| `title` | `String` | 标题，取首条消息前 20 字 |
| `createdAt` | `LocalDateTime` | 创建时间 |
| `updatedAt` | `LocalDateTime` | 更新时间（首条消息入库时同步更新） |

### 2.2 `Message`（`message` 表）

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | `Long` | 主键，自增 |
| `conversationId` | `Long` | 所属对话 id（软关联） |
| `role` | `String` | `user` / `assistant`（本 MVP 仅生成 user 消息） |
| `content` | `String` | 消息内容，最大 4000 字符 |
| `attachedPackageId` | `Long` | 关联素材包 id（可空） |
| `taskId` | `Long` | 触发的处理任务 id（可空，需求 5.6） |
| `createdAt` | `LocalDateTime` | 创建时间 |

## 3. 端点（`ConversationController`，`/api/conversation`）

| 方法 | 路径 | 用途 |
|---|---|---|
| `POST` | `/create` | 创建空对话 |
| `GET` | `/list` | 对话列表（分页，按 `created_at` 降序） |
| `GET` | `/{conversationId}/messages` | 消息历史（按 `created_at` 升序） |
| `POST` | `/{conversationId}/send` | 发送消息（持久化 + 触发 DAG 解析） |
| `POST` | `/{conversationId}/confirm` | 确认执行（**委托** `TaskService.confirm`，见 `module-task.md`） |

> `/confirm` 端点放在 `ConversationController` 是为了贴合前端「从对话页点击确认」的场景，业务实现统一由 `TaskService` 完成。

## 4. 服务实现 `ConversationService`

### 4.1 常量

| 常量 | 值 | 含义 |
|---|---|---|
| `CONTENT_MAX_LENGTH` | 4000 | 消息内容最大字符数（需求 5.3、5.7） |
| `TITLE_MAX_LENGTH` | 20 | 标题截取上限（需求 5.2） |
| `ROLE_USER` / `ROLE_ASSISTANT` | `"user"` / `"assistant"` | 角色 |

### 4.2 方法

| 方法 | 行为 |
|---|---|
| `create()` | 插入 `Conversation`，写 `createdAt = updatedAt = now()`，返回新 id |
| `list(page, size)` | `Pagination.of` 校验分页；`orderByDesc("created_at").orderByDesc("id")` 稳定排序；`PageResponse<ConversationListItem>` |
| `getMessages(conversationId)` | 校验对话存在；DB `orderByAsc("created_at", "id")` + 服务层防御性二次排序（`nullsLast`）；返回 `List<MessageItem>` |
| `sendMessage(conversationId, request)` | 校验链：对话存在 → 内容校验 → 附件素材包就绪校验；持久化 `Message`；首条消息时更新 `Conversation.title` 与 `updatedAt` |
| `attachTask(messageId, taskId)` | 消息存在校验；写 `taskId`（需求 5.6） |
| `validateContent(content)`（static） | 非 null / 非纯空白 / 长度 ≤ 4000 |
| `deriveTitle(content)`（static） | `min(20, length)` 截取；纯函数，便于属性测试 |

### 4.3 发送消息校验链

```
requireConversation(id)           ── CONVERSATION_NOT_FOUND
        │
        ▼
validateContent(content)         ── MESSAGE_CONTENT_INVALID
        │   • null / 空白
        │   • 长度 > 4000（含 details）
        ▼
attachedPackageId != null ?
        ├── 是 → validatePackageAvailable(id)
        │          ── 素材包不存在 / status != READY → PACKAGE_UNAVAILABLE
        │             （details 含 attachedPackageId / requiredStatus / actualStatus）
        └── 否 → 跳过
        │
        ▼
countMessages == 0 ?             ── firstMessage 标志
        │
        ▼
insert Message（role=user, content, attachedPackageId, createdAt=now）
        │
        ▼
firstMessage ?
        ├── 是 → Conversation.title = deriveTitle(content)
        │         updatedAt = now
        │         conversationMapper.updateById
        └── 否 → 跳过
        │
        ▼
return MessageItem.from(message)
```

> **校验失败一律不持久化任何 message 记录**（需求 5.7、5.8）。

### 4.4 消息列表升序不变量

`getMessages` 主动做二次排序（`comparing(Message::getCreatedAt, nullsLast(naturalOrder)).thenComparing(Message::getId, ...)`），即便底层 MyBatis 顺序不确定，前端拿到的也是按 `created_at`（次级 `id`）严格升序的列表（Property 16）。

## 5. DTO

| DTO | 字段 | 用途 |
|---|---|---|
| `ConversationCreateResponse` | `conversationId` | 创建对话响应 |
| `ConversationListItem` | `id, title, createdAt, updatedAt` | 列表项 |
| `MessageItem` | `id, conversationId, role, content, attachedPackageId, taskId, createdAt` | 消息项（消息历史与发送响应复用） |
| `SendMessageRequest` | `content, attachedPackageId` | 发送请求体 |
| `SendMessageResponse` | `messageId, needConfirm, missingParams, dagPreview, reply, taskId` | 发送响应；由 `messageId` + `DagParseResult` 通过 `SendMessageResponse.of` 组装 |

> `SendMessageResponse.taskId` **恒为 null**——任务在 `/confirm` 时由 `TaskService` 创建。

## 6. 不变量

1. **首条消息决定标题**：只有当 `countMessages == 0` 时才更新 `Conversation.title`，避免后续消息覆盖。
2. **校验失败原子性**：校验链任一环节失败都不写任何 `message` 记录。
3. **发送即触发解析**：`ConversationController.send` 调 `ConversationService.sendMessage` 持久化后立即 `DagParser.parse`，两阶段在同一请求中完成。
4. **消息升序不依赖单一来源**：DB `ORDER BY` + 服务层二次排序（`nullsLast` + `id` 兜底）。
5. **附件素材包就绪硬约束**：发送时若指定 `attachedPackageId`，必须存在且 `status == READY`。
6. **任务关联由 `attachTask` 显式写入**：`/confirm` 创建任务后由 `TaskService` 调用 `ConversationService.attachTask(messageId, taskId)`，保持单一写入路径。

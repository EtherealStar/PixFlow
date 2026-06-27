# PixFlow 前端设计文档

> 适用版本：`frontend@0.1.0`
> 技术栈：Vue 3 + Vite 5 + Vue Router 4 + Element Plus 2 + Axios
> 默认后端地址：`http://localhost:8080`（通过 Vite 代理转发 `/api`）

---

## 1. 概述

PixFlow 前端是一个面向运营/设计人员的批量图片处理工作台。它把"上传素材 → 用自然语言下达指令 → 后端解析生成 DAG → 确认执行 → 查看/下载结果"这条链路以三屏流程串起来，让没有图像处理经验的用户通过对话方式完成原本需要写脚本或调工具链的工作。

### 1.1 设计目标

- **零代码指令下发**：用户只需输入自然语言，前端负责把解析后的 DAG 渲染出来再确认执行。
- **所见即所得**：结果页提供缩略图网格 + 大图预览 + 单任务打包下载。
- **结构清晰**：原始素材与加工结果分屏管理；任务作为连接对话与结果的中间产物独立可查。
- **后端耦合最小化**：所有接口集中在 `src/api/index.js`，UI 不感知 baseURL、错误码等底层细节。

### 1.2 角色与典型流程

1. **运营** 上传 zip 素材包（可选附 Excel/CSV 文案文档）。
2. **运营** 新建对话 → 选择已就绪素材包 → 输入"去背景、白底、压到200KB、右下角加水印，并生成卖点文案"。
3. **运营** 在 DAG 预览中确认节点与参数 → 点击"确认执行"。
4. **运营** 在任务结果页查看进度、查看结果图、打包下载。

---

## 2. 总体架构

```
frontend/
├── index.html                 # 入口 HTML，挂载 #app
├── vite.config.js             # Vite 配置：端口 5173，/api 代理至 :8080
├── package.json               # 依赖与脚本
└── src/
    ├── main.js                # 应用入口：注册 ElementPlus、Icons、Router
    ├── App.vue                # 顶层布局：顶栏 Logo + 三 Tab 导航 + <router-view/>
    ├── router/index.js        # 路由表（懒加载）
    ├── api/index.js           # axios 实例 + 四大业务 API 封装
    ├── utils/format.js        # 状态映射、formatSize、formatTime
    └── views/
        ├── FileManager.vue    # /files —— 文件管理（双子页面）
        ├── Conversation.vue   # /conversation —— 对话处理
        └── TaskResult.vue     # /tasks —— 任务结果
```

### 2.1 启动与构建

| 命令 | 作用 |
|---|---|
| `npm install` | 安装依赖 |
| `npm run dev` | 启动开发服务器（`http://localhost:5173`），`/api/*` 代理至 `http://localhost:8080/*` |
| `npm run build` | 生产构建，产物输出到 `dist/` |
| `npm run preview` | 本地预览构建产物 |

> 之所以用相对路径预览（`r.previewUrl`）而不是后端绝对 URL，是因为 Vite 的 `/api` 代理让前端同源即可访问后端静态资源，开发期零额外配置；生产部署时前端与后端在同一域名下同理可用。

---

## 3. 全局机制

### 3.1 路由

`createWebHistory` 模式，三条顶层路由（懒加载）：

| 路径 | 名称 | 视图组件 |
|---|---|---|
| `/` | — | 重定向到 `/files` |
| `/files` | `files` | `views/FileManager.vue` |
| `/conversation` | `conversation` | `views/Conversation.vue` |
| `/tasks` | `tasks` | `views/TaskResult.vue` |

顶栏 `<el-menu>` 通过计算当前路径首段高亮 active 菜单项。

### 3.2 HTTP 客户端

文件：`src/api/index.js`

```js
const http = axios.create({
  baseURL: '/api',
  timeout: 600000   // 同步执行任务可能较慢
})
```

- 响应拦截器统一返回 `response.data`，视图层无需关心 HTTP 层。
- 错误拦截器读取后端约定的错误结构 `{ code, message, details }`，通过 `ElMessage.error` 统一提示，并把原始 `error` 抛出以便业务 catch 后做局部处理（例如删除确认后展示 `deletedFileCount`）。

### 3.3 公共 API 封装

`api/index.js` 按业务领域分组导出，视图只依赖这些具名函数：

```js
assetApi.list / detail / upload / remove
resultApi.list / downloadUrl / rawUrl
conversationApi.create / list / messages / send / confirm
taskApi.list / detail
```

`downloadUrl(taskId)` 与 `rawUrl(resultId)` 返回字符串相对 URL（如 `/api/asset/result/download/1`），由 `window.open` 或 `<el-image :src>` 直接使用，**不经过 axios**，避开 responseType 与跨域麻烦。

### 3.4 工具函数

`utils/format.js`：

- `PACKAGE_STATUS` / `TASK_STATUS` / `RESULT_STATUS`：数字 → `{ label, type }` 映射（Element Plus `<el-tag type>`）。
- `formatSize(bytes)`：自适应 B/KB/MB/GB，小于 10 时保留 1 位小数。
- `formatTime(value)`：把 `LocalDateTime` 字符串（如 `2026-06-24T10:30:00`）展示为 `YYYY-MM-DD HH:MM:SS`。

---

## 4. 页面设计

### 4.1 全局布局（App.vue）

```
┌──────────────────────────────────────────────────────────────┐
│  [🖼 PixFlow  自然语言驱动的批量图片处理]   文件管理  对话  任务 │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│                       <router-view />                        │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

- 顶栏白色 + 灰底主区域 (`#f5f7fa`)。
- 三个一级菜单：`/files`、`/conversation`、`/tasks`，对应 Element Plus `Folder / ChatDotRound / List` 图标。
- 主区域自适应滚动；子页面通过 `.page-card` 卡片包裹。

### 4.2 文件管理（`/files`，FileManager.vue）

双子 Tab 设计：**原始文件** / **加工后文件**。

#### 4.2.1 原始文件 Tab

**功能**

- 列表展示所有素材包，支持分页、排序（按 `name / size / createdAt` 升降）、状态标签（解析中/就绪/解析失败）。
- 上传素材包：选 zip（必填）+ 选填 Excel/CSV 文案文档；上传完成后展示识别结果（图片数 / 跳过文件 / 失败原因）。
- 详情：弹窗显示素材包元数据 + 图片列表（imageId / skuId / originalPath）。
- 删除：弹窗二次确认，后端返回 `deleted` 与 `deletedFileCount`，分别给出成功/部分失败提示。

**页面字段**

| 列名 | 字段 | 说明 |
|---|---|---|
| ID | `id` | 素材包 ID |
| 包名 | `name` | 原始包名 |
| 大小 | `size` | 字节数，formatSize 格式化 |
| 图片数 | `imageCount` | 后端解析得到 |
| 状态 | `status` | 0 解析中 / 1 就绪 / 2 解析失败 |
| 创建时间 | `createdAt` | formatTime 格式化 |

**API 调用**

- `GET /api/asset/package/list?page=&size=&sortBy=&order=` → 分页 + 排序。
- `GET /api/asset/package/{id}` → 详情。
- `POST /api/asset/package/upload`（multipart）→ 上传 + 解析结果。
- `DELETE /api/asset/package/{id}` → 删除，返回 `{ deleted, deletedFileCount }`。

#### 4.2.2 加工后文件 Tab

**功能**

- 按 `taskId` 过滤（任务下拉来自 `taskApi.list`，加载 Tab 时一次性拉取前 100 条）。
- 结果图卡片网格（每张卡片 = 缩略图 + SKU + 状态 + 任务/支路 + 生成文案 + 错误信息）。
- 选中任务后 "打包下载" 调用 `/api/asset/result/download/{taskId}`（浏览器原生下载）。
- 单图点击触发 Element Plus `<el-image>` 内置大图预览。

**页面字段**

| 字段 | 说明 |
|---|---|
| `skuId` | 商品 SKU |
| `taskId` | 所属任务 |
| `branchId` | DAG 支路 ID |
| `status` | 0 待处理 / 1 成功 / 2 失败 |
| `previewUrl` | 缩略图 URL（相对路径） |
| `generatedCopy` | 生成文案（可选） |
| `errorMsg` | 失败原因（可选） |

**API 调用**

- `GET /api/asset/result/list?page=&size=&taskId=` → 分页结果。
- `GET /api/task/list?page=1&size=100` → 任务下拉。
- `GET /api/asset/result/download/{taskId}` → 浏览器跳转下载。
- 单图 `previewUrl` 通常是后端返回的相对路径（由 `/api/asset/result/{id}/raw` 或类似端点提供）。

### 4.3 对话处理（`/conversation`，Conversation.vue）

三栏交互：**对话列表 / 主对话区 / 解析与执行反馈**。布局：`flex` 左右两栏，左侧 260px 对话列表，右侧主区自适应。

#### 4.3.1 对话列表

- 列表项展示标题（默认"（新对话）"）+ `updatedAt`。
- 顶部"新建"按钮调用 `conversationApi.create()`，刷新列表并自动选中。
- 选中调用 `selectConversation(id)`：写 `activeId` 并加载消息历史。

#### 4.3.2 主对话区（消息流）

- 自上而下三段：消息流（可滚动，自动滚到底）/ 解析面板 / 输入区。
- 消息气泡：左侧 `assistant` 灰底，右侧 `user` 蓝底；每条消息显示文案、附带的素材包 ID（📎）/ 触发的任务 ID（✓）以及时间。
- 空状态提示"发送第一条指令"。

#### 4.3.3 输入区

- 顶部一行：素材包下拉（仅展示 `status === 1` 即"就绪"的素材包）+ 刷新按钮。
- 文本域：`maxlength=4000`，`show-word-limit`，`Enter`（不带 Shift）触发发送（`@keydown.enter.exact.prevent="send"`）。
- 发送按钮带 loading 态。

#### 4.3.4 解析面板

`POST /conversation/{id}/send` 返回体：

```json
{
  "reply": "string",
  "needConfirm": true,
  "missingParams": [ { "nodeId": "n1", "param": "watermarkText" } ],
  "dagPreview": { "nodes": [...], "edges": [...] }
}
```

UI 表现：

- 顶部 Alert 显示 `reply`。
- 若 `missingParams` 非空，列出每条 `节点 - 缺少参数`。
- 若 `dagPreview` 存在，渲染节点标签 + "确认执行"按钮（`canConfirm` = 存在 DAG 预览 **且** 选中素材包）。

#### 4.3.5 确认执行

`POST /conversation/{id}/confirm` 请求体：

```json
{
  "dagJson": "<DAG JSON 字符串>",
  "packageId": 123
}
```

响应（`confirmResult`）：

```json
{
  "taskId": 456,
  "status": 2,            // 2=完成 / 其他视为含失败
  "totalCount": 30,
  "doneCount": 30,
  "resultPreviewUrls": ["/api/asset/result/1/raw", "..."]
}
```

UI 表现：Alert + 缩略图横排（可点开大图预览）。

#### 4.3.6 API 调用汇总

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | `/conversation/create` | 新建对话，返回 `conversationId` |
| GET | `/conversation/list?page=&size=` | 对话列表 |
| GET | `/conversation/{id}/messages` | 历史消息 |
| POST | `/conversation/{id}/send` | 发送指令，返回解析结果 |
| POST | `/conversation/{id}/confirm` | 确认执行，返回 task 概览 |

### 4.4 任务结果（`/tasks`，TaskResult.vue）

#### 4.4.1 任务列表

- 顶栏 `el-radio-group` 状态过滤器：全部 / 待执行 / 执行中 / 完成 / 失败。
- 表格列：任务 ID / 素材包 / 对话 / 状态 / 进度 / 创建时间 / 完成时间 / 操作（详情 / 下载）。
- 进度：`<el-progress :percentage>`，失败时 `status="exception"`，完成时 `status="success"`。

#### 4.4.2 任务详情（Drawer，宽 60%）

- 顶部 Descriptions：任务 ID、素材包、状态（tag）、总数、完成数、完成时间。
- DAG 结构：`<el-input type="textarea" readonly>` 展示格式化后的 `dagJson`（`prettyDag()` 自动 `JSON.parse` 再 `stringify(_, null, 2)`，失败回退原值）。
- 打包下载按钮（顶部右侧）调用 `/api/asset/result/download/{taskId}`。
- 处理结果：网格卡片，字段同 §4.2.2 中的"加工后文件 Tab"。
- 分页器：仅 prev/pager/next，`page-size` 固定 12；切换页时通过 `taskApi.detail(taskId, { resultPage, resultSize })` 让后端分页。

#### 4.4.3 API 调用汇总

| 方法 | 路径 | 用途 |
|---|---|---|
| GET | `/task/list?page=&size=&status=` | 任务列表 + 过滤 |
| GET | `/task/{id}?resultPage=&resultSize=` | 任务详情 + 分页结果 |
| GET | `/api/asset/result/download/{taskId}` | 浏览器跳转打包下载 |

---

## 5. 状态与交互流程

### 5.1 端到端数据流

```
用户上传 zip ──> POST /asset/package/upload ──> 返回 packageId + status
                                       │
                                       ▼
                list 轮询或刷新 ──> status 变为 1 (就绪)
                                       │
                                       ▼
用户发起对话 ──> POST /conversation/{id}/send (content + packageId)
                                       │
                                       ▼
                          reply / needConfirm / missingParams / dagPreview
                                       │
                          (若缺参 → 调整 prompt 后再次 send)
                                       │
                                       ▼
用户点击确认执行 ──> POST /conversation/{id}/confirm (dagJson + packageId)
                                       │
                                       ▼
                              taskId + resultPreviewUrls
                                       │
                                       ▼
             跳到 /tasks 查看进度，或在 /conversation 看执行结果缩略图
```

### 5.2 关键交互约束

- **素材包就绪**：对话页只能选择 `status === 1` 的素材包；过滤在客户端进行（`packages.value.filter(p => p.status === 1)`）。
- **可确认条件**：`canConfirm = parse.dagPreview && selectedPackage.value`，按钮 `:disabled` 联动，避免无素材包时误触。
- **执行反馈**：根据 `confirmResult.status === 2` 区分 "完成" 与 "执行结束（含失败）" 两类 Toast 文案。
- **超时**：axios `timeout = 600s`，覆盖 confirm 长同步调用；后续如改异步任务可考虑降级。

### 5.3 错误处理约定

| 来源 | 表现 |
|---|---|
| axios 拦截器 | 顶部红 Toast（`ElMessage.error(message)`），Promise reject 给调用方 |
| 业务 catch | 删除素材包根据 `deleted` 标志显示"删除完成/未完成"两种文案 |
| 输入校验 | 缺素材包、缺指令内容等轻提示用 `ElMessage.warning` |

---

## 6. 样式约定

- 主题色：`#409eff`（Element Plus 默认蓝）。
- 主背景：`#f5f7fa`，卡片白底 + `shadow="never"` 保持轻量分隔。
- 字体栈：`-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, ...`。
- 圆角 8px 消息气泡、卡片缩略图 `minmax(200px, 1fr)` / 详情页 `minmax(180px, 1fr)` 自适应网格。
- DAG 节点标签用 `<el-tag effect="plain">` + 等宽字体突出。

---

## 7. 后续可演进点

1. **任务进度实时化**：当前任务列表靠用户点"刷新"，可接入 SSE/WebSocket 推 `doneCount`。
2. **对话消息 Markdown 渲染**：assistant 回复若带列表/代码需更丰富的展示。
3. **结果筛选/排序**：加工结果 Tab 增加按状态、按支路过滤。
4. **多素材包批处理**：当前一个 task = 一个 package，可扩展支持多包并行。
5. **DAG 可视化**：当前仅平铺节点 tag，后续可引入 dagre/d3 渲染拓扑图。
6. **国际化**：`html lang="zh-CN"` 与所有文案中文写死；引入 `vue-i18n` 后可中英双语。
7. **鉴权**：当前无登录态，生产部署需在 axios 拦截器加 token 注入 + 路由守卫。

---

## 8. 附录：接口契约摘要

> 所有请求通过 Vite 代理至 `http://localhost:8080`，统一前缀 `/api`。

### 8.1 素材包（Asset Package）

| Method | Path | 入参 | 出参 |
|---|---|---|---|
| GET | `/asset/package/list` | `page, size, sortBy, order` | `{ records: [Package], total }` |
| GET | `/asset/package/{id}` | — | `PackageDetail { id, name, size, imageCount, status, createdAt, images: [{imageId, skuId, originalPath}] }` |
| POST | `/asset/package/upload` | `multipart: zip_file, doc_file?` | `{ packageId, name, imageCount, status, failureReason?, skippedFiles? }` |
| DELETE | `/asset/package/{id}` | — | `{ deleted: boolean, deletedFileCount }` |

### 8.2 加工结果（Asset Result）

| Method | Path | 入参 | 出参 |
|---|---|---|---|
| GET | `/asset/result/list` | `page, size, taskId?` | `{ records: [Result], total }` |
| GET | `/asset/result/{id}/raw` | — | 二进制图片（直链，供 `<img>` 渲染） |
| GET | `/asset/result/download/{taskId}` | — | 二进制 zip（浏览器跳转触发下载） |

`Result { id, taskId, branchId, skuId, status, previewUrl, generatedCopy?, errorMsg? }`

### 8.3 对话（Conversation）

| Method | Path | 入参 | 出参 |
|---|---|---|---|
| POST | `/conversation/create` | — | `{ conversationId }` |
| GET | `/conversation/list` | `page, size` | `{ records: [Conversation], total }` |
| GET | `/conversation/{id}/messages` | — | `[Message]` |
| POST | `/conversation/{id}/send` | `{ content, attachedPackageId? }` | `{ reply, needConfirm, missingParams: [{nodeId, param}], dagPreview: { nodes: [...], edges: [...] } }` |
| POST | `/conversation/{id}/confirm` | `{ dagJson: string, packageId }` | `{ taskId, status, totalCount, doneCount, resultPreviewUrls: [...] }` |

### 8.4 任务（Task）

| Method | Path | 入参 | 出参 |
|---|---|---|---|
| GET | `/task/list` | `page, size, status?` | `{ records: [Task], total }` |
| GET | `/task/{id}` | `resultPage, resultSize` | `{ id, packageId, status, totalCount, doneCount, createdAt, finishedAt, dagJson, results: [Result] }` |

### 8.5 通用错误结构

```json
{ "code": "STRING_CODE", "message": "可读错误信息", "details": { /* 可选 */ } }
```

由 axios 响应拦截器统一 Toast 提示并 `Promise.reject`，业务可在 `try/catch` 中进一步处理。

---

## 9. 字段枚举

- `PACKAGE_STATUS`: 0=解析中 / 1=就绪 / 2=解析失败
- `TASK_STATUS`: 0=待执行 / 1=执行中 / 2=完成 / 3=失败
- `RESULT_STATUS`: 0=待处理 / 1=成功 / 2=失败

详见 `src/utils/format.js`。
# PixFlow 后端 — 总体设计文档

> 对应源码根目录 `src/main/java/com/etherealstar/pixflow/`。
> 前端设计文档见 `design-doc/frontend.md`。

## 1. 目标与定位

PixFlow 是一款面向电商运营人员的「**自然语言 → 批量图片处理**」工具：运营上传一个 zip 素材包（可附文案文档）后，用一句话下达指令（如「去背景、白底、压到 200KB、右下角加水印，并生成卖点文案」），后端将指令解析为可执行的工具编排图（DAG），在确认后并发地处理整包图片，并生成营销文案。

整个系统由三个角色协同：

- **运营**：上传素材包、对话下达指令、确认 DAG 预览、查看并下载结果。
- **后端（PixFlow Backend）**：Spring Boot 应用，负责素材管理、LLM 驱动的 DAG 解析、服务端校验、并发批处理、结果预览与打包下载。
- **LLM（外部）**：通过 Spring AI 抽象接入的对话模型（如 DeepSeek），仅用于把自然语言指令解析为 DAG JSON。

> MVP 范围不做用户鉴权（无登录/权限），但对上传内容、消息内容、参数取值等执行严格校验，确认执行时一律服务端重新校验 DAG。

## 2. 顶层架构

### 2.1 模块总览

后端按 `module/<name>` 切分业务域，`infra/` 与 `common/` 提供横切能力：

```
src/main/java/com/etherealstar/pixflow
├── PixFlowApplication.java         # Spring Boot 入口
├── common/
│   ├── error/                      # 统一错误响应与异常拦截
│   └── web/                        # 分页响应 / 参数校验
├── infra/                          # 横切基础设施
│   ├── ai/                         # LLM 抽象、提示词、工具白名单
│   ├── image/                      # 图像编解码 + 像素工具执行器
│   ├── storage/                    # 本地文件存储抽象与目录约定
│   └── config/                     # MyBatis Plus 配置
└── module/
    ├── file/                       # 素材包（zip + 文案 + 图片）
    ├── dag/                        # DAG 解析 / 校验 / 执行引擎
    ├── conversation/               # 对话与消息
    └── task/                       # 任务管理与结果下载
```

### 2.2 模块职责一句话描述

| 模块 | 职责 |
|---|---|
| `common.error` | 全局 `BusinessException` / `ErrorCode` / `ErrorResponse` / `@RestControllerAdvice` 统一错误响应 |
| `common.web` | `PageResponse<T>` 与 `Pagination` 分页参数校验（1 ≤ page, 1 ≤ size ≤ 100） |
| `infra.ai` | `LlmClient` 抽象与 Spring AI 实现、工具白名单 `ToolCatalog`、DAG 解析提示词 `DagPromptManager` |
| `infra.image` | 抠图客户端抽象、图像编解码（解码 / 编码 / 按 maxKb 降质循环）、像素工具执行器 `ImageToolExecutor`（6 种像素工具） |
| `infra.storage` | `StorageService` 抽象与 `LocalFileStorageService` 实现；`StoragePaths` 集中目录约定；`StorageProperties` 绑定根目录 |
| `infra.config` | MyBatis Plus 分页插件 + Mapper 扫描 |
| `module.file` | 素材包上传（zip-bomb 防护 + 图片识别 + SKU 提取 + 文案文档解析）、列表 / 详情 / 删除、结果图预览与打包下载 |
| `module.dag` | DAG 领域对象、`DagParser`（LLM 解析 + 缺参追问）、`DagNormalizer`（确定性排序）、`DagValidator`（服务端严格校验）、`DagJsonCodec`（序列化往返）、执行引擎 `DagExecutionEngine`（分支展开 + 拓扑执行 + 失败隔离） |
| `module.conversation` | 对话 CRUD、消息历史、发送消息 + 校验链、触发 DAG 解析 |
| `module.task` | 任务创建（确认执行）、任务列表 / 详情、结果分页与流式打包下载 |

### 2.3 分层与依赖方向

```
controller  ──►  service  ──►  mapper  (MyBatis Plus)
                  │
                  ├──►  infra.*  (storage / image / ai)
                  └──►  module.<其它>.*  (跨模块仅按需调用 service / domain)
```

- **controller** 只做参数接收与响应装配，校验逻辑统一由 `service` 触发并以 `BusinessException` 上抛。
- **service** 负责业务规则、跨模块编排、事务边界。
- **mapper** 仅暴露 `BaseMapper<T>` 的标准 CRUD，不写 SQL XML。
- **infra** 是不依赖任何 `module.*` 的横切层，**`module.dag` 的领域对象是纯 POJO**，可被其它模块无环依赖地引用。

## 3. 技术栈与版本

| 维度 | 选型 | 说明 |
|---|---|---|
| 语言 / 框架 | Java + Spring Boot | 入口 `PixFlowApplication`，`@ConfigurationPropertiesScan` 全局属性绑定 |
| 持久化 | MyBatis Plus | 分页插件 `PaginationInnerInterceptor(DbType.MYSQL)`，单页 maxLimit 500 |
| 数据库 | MySQL 8 | 业务库 `pixflow`（`jdbc:mysql://localhost:3306/pixflow`），schema 见 `src/main/resources/db/schema.sql` |
| LLM 接入 | Spring AI | 通过 `ObjectProvider<ChatModel>` 惰性获取，避免未配置 starter 时启动失败；当前 `application.yml` 默认走 DeepSeek |
| 图像处理 | `java.awt.image.BufferedImage` + `javax.imageio.ImageIO` + Thumbnailator | 像素工具与缩放；无 WebP 写出器时相关格式按「无法解码」记入跳过清单 |
| 文件存储 | 本地磁盘 | 存储根目录 `pixflow.storage.root`（默认 `./data/pixflow-storage`），相对路径解析在 `LocalFileStorageService` 内做路径穿越校验 |
| 文档解析 | Apache POI（xls/xlsx）+ 手写 CSV 解析（UTF-8，支持双引号与 `""` 转义） | `CopyDocumentParser` |

## 4. 端到端业务流

### 4.1 素材包上传

1. `POST /api/asset/package/upload`（`AssetPackageController`）
2. `AssetPackageService.upload`
   - 校验 zip 存在与体积上限（`ASSET_ZIP_TOO_LARGE`）。
   - 解析文案文档（`CopyDocumentParser`）：后缀 / 体积 / 行数 / 表头列名校验，缺 `id` 列直接拒绝。
   - 先建 `asset_package`（`status=PARSING`），落盘 zip 与文案文档；通过本地 zip 头签名（4 字节）二次校验。
   - `ZipExtractor` 流式解压，做 zip-bomb 防护：累计文件数 / 累计解压大小任一超阈值则 `ASSET_ZIP_BOMB`。
   - `ImageValidator` 对每条目：扩展名白名单（JPG/JPEG/PNG/WebP）+ ImageIO 解码校验；通过的图片落盘并由 `SkuExtractor` 提取 SKU。
   - 文案行入库 `asset_copy`（`sku_id` 与 `asset_image` 软关联，无外键）。
   - 根据识别数判定 `status`（READY / PARSE_FAILED）。
3. 异常时 `cleanup(packageId)` 删除已落盘目录与三张表记录。

### 4.2 对话与指令解析

1. `POST /api/conversation/create` → 新建对话。
2. `POST /api/conversation/{id}/send`（`ConversationService.sendMessage` + `DagParser.parse`）
   - 校验链：对话存在 → 内容非空白且 ≤ 4000 → 附件素材包（若有）状态 `READY`。任一失败不持久化消息。
   - 持久化 `message`（`role=user`），若是首条消息则将 `conversation.title` 设为 `min(20, content.length())` 个字符。
   - `DagParser.parse`：通过 `DagPromptManager` 拼装系统提示词（含工具白名单 + 参数 schema + 固定输出格式），调用 `LlmClient` 解析指令；经 `DagNormalizer` 确定性排序；再按 `ToolSchemaRegistry` 缺参检查。
   - 返回 `DagParseResult`（`needConfirm=true`，`taskId=null`）：**缺参追问**或**预览待确认**二选一。

### 4.3 确认执行

1. `POST /api/conversation/{id}/confirm`（`TaskService.confirm`）
   - 校验对话存在、`packageId` 就绪。
   - **`DagValidator.validateJson(dagJson)`** 服务端独立校验（结构、节点数、工具白名单、参数 schema、边引用、无环），不信任前端回传。
   - 创建 `process_task`（`status=0`，`dagJson` 由 `DagJsonCodec` 序列化已校验的 `Dag`，保证序列化往返一致）。
   - `DagExecutionEngine.execute`：
     - `BranchExpander` 将 DAG 展开为 source→sink 支路，每条支路分配唯一 `branchId`。
     - 按「图片 × 支路」生成工作单元，`ImageWorkerPool` 固定大小线程池（默认 8）并发执行。
     - 每条像素支路按支路顺序应用 `ImageToolExecutor` 六个像素工具，`ImageCodec.encode` 落盘并写 `process_result`。
     - 含 `generate_copy` 的支路由 `CopyGenerator` 走 LLM 生成文案并截断到 `copy-max-length`。
     - `FailureIsolator` 单单元失败隔离（status=2、error_msg 截断、output_path 清空）。
   - 终态：至少一条成功 → 完成（2）；全失败 → 失败（3）。
   - 响应携带按 `asset_image.id` 升序的前 `min(3, n)` 张成功结果预览 URL。

### 4.4 查询与下载

- 任务：`GET /api/task/list`（分页 + status 筛选）、`GET /api/task/{id}`（分页结果列表）。
- 结果：`GET /api/asset/result/list`（分页 + taskId 筛选）、`GET /api/asset/result/{id}/preview`（返回 URL）、`GET /api/asset/result/{id}/raw`（流式字节）、`GET /api/asset/result/download/{taskId}`（`StreamingResponseBody` 流式 zip）。
- 素材包：`GET /api/asset/package/list`（分页 + 排序）、`GET /api/asset/package/{id}`（含图片列表）、`DELETE /api/asset/package/{id}`（级联清理）。

## 5. 关键技术决策

### 5.1 软关联

- `asset_image.sku_id` ↔ `asset_copy.sku_id` ↔ `process_result.sku_id` 通过业务键软关联，**全库无数据库外键**。
- `process_task.conversation_id` / `process_task.package_id` / `process_result.task_id` / `process_result.image_id` 同样为软关联。
- 业务上的「引用关系」由 `PackageDeleter` 在删除素材包前主动检查 `process_task.package_id` 引用（被引用则 `PACKAGE_REFERENCED_BY_TASK`）。

### 5.2 LLM 调用隔离

- 所有 LLM 调用必须通过 `infra.ai.LlmClient` 接口。
- `SpringAiLlmClient` 通过 `ObjectProvider<ChatModel>` 惰性获取 Bean，未配置 starter 时应用仍可启动，仅在真正调用时报错。
- DAG_Parser 与 CopyGenerator 内部都只依赖 `LlmClient` 抽象，便于测试中以内存替身替代。

### 5.3 服务端独立校验

- 不信任前端回传的 `dagJson`：确认执行时一律 `DagValidator.validateJson` 重新校验（结构 / 节点数 / 工具白名单 / 参数 schema / 边引用 / 无环）。
- DAG 序列化由 `DagJsonCodec.write(Dag)` 从已校验的领域对象产出，保证与 `DagValidator` 解析互逆（Property 35：序列化往返一致）。

### 5.4 失败隔离

- 单「图片 × 支路」工作单元在 `DagExecutionEngine.processUnit` 中捕获任意 `Throwable`，由 `FailureIsolator` 将对应 `ProcessResult` 标记为 `status=2`、`error_msg` 截断到 1000 字符、`output_path` 清空。
- 同图其余支路、批次其余图片、其余 SKU 的文案生成均继续处理。

### 5.5 确定性规范化

- `DagNormalizer.normalize` 用 Kahn 拓扑选择：就绪节点中若仍有 `convert_format` 未放置则延后所有 `compress` 节点（仅当无其他可选项时才被迫选择 `compress`），其余按原始声明位置升序——保证「先转格式再压缩」共现时唯一确定（需求 6.4）。

### 5.6 流式 zip 与流式图片

- `ResultDownloadService.streamZip` 用 `StreamingResponseBody` 逐文件拷贝至 `ZipOutputStream`，峰值常驻内存只与单个文件缓冲区相关。
- `ZipExtractor.extract` 用 `ZipInputStream` 流式逐条目解压，按块累计大小，单条目越界立即终止。

## 6. 数据模型（逻辑视图）

| 表 | 关键字段 | 备注 |
|---|---|---|
| `asset_package` | id, name, zip_path, doc_path, size, image_count, status(0/1/2), created_at | 主表 |
| `asset_image` | id, package_id, sku_id, original_path, created_at | 重复 SKU 不去重 |
| `asset_copy` | id, package_id, sku_id, product_name, keywords, description, created_at | 与 image 通过 sku_id 软关联 |
| `conversation` | id, title, created_at, updated_at | 标题取首条消息前 20 字 |
| `message` | id, conversation_id, role(user/assistant), content, attached_package_id, task_id, created_at | content ≤ 4000 字符 |
| `process_task` | id, conversation_id, package_id, dag_json, status(0/1/2/3), total_count, done_count, created_at, finished_at | 同步执行，status 0→2/3 |
| `process_result` | id, task_id, image_id, sku_id, branch_id, output_path, generated_copy, status(0/1/2), error_msg, created_at | 同图多支路 branch_id 唯一 |

> 数据库 schema 见 `src/main/resources/db/schema.sql`。

## 7. 文档导航

| 主题 | 文档 |
|---|---|
| 全局错误处理与分页 | `design-doc/common.md` |
| LLM / 图像 / 存储基础设施 | `design-doc/infra.md` |
| 素材包模块 | `design-doc/module-file.md` |
| DAG 解析 / 校验 / 执行 | `design-doc/module-dag.md` |
| 对话模块 | `design-doc/module-conversation.md` |
| 任务模块 | `design-doc/module-task.md` |
| 前端 | `design-doc/frontend.md` |

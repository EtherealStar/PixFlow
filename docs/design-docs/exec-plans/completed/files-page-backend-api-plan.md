# /files 页面后端图片管理接口补齐

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划按仓库根目录的 `PLANS.md` 维护。本文是自包含的中文执行计划：后续执行者只需要当前工作树和本文，就能理解为什么要做、改哪些文件、按什么顺序实现、如何验证行为。

## Purpose / Big Picture

当前 `/files` 页面已经有“素材”和“产物”两个视图，但后端接口还不支撑高效渲染与细粒度管理。素材侧只能查素材包详情，拿不到包内图片列表和预签名预览 URL；产物侧需要前端按“会话列表 → 任务列表 → 结果列表 → 单结果下载 URL”层层请求，形成严重的 N+1 请求链；删除、重命名、自定义打包下载也还停留在前端本地演示。

完成本计划后，用户打开 `/files` 页面时，前端可以用少量聚合接口直接拿到素材图片和会话产物图片，图片条目自带可展示的预签名 URL；用户可以删除或重命名单张/多张素材与产物，并能把任意选中的素材图、产物图打成临时 ZIP 下载。可见的验收效果是：打开 `/files`，素材和产物不再依赖 mock 或多层循环请求；删除、重命名、批量下载动作会真实落到后端事实源。

## Progress

- [x] (2026-07-04 15:30+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/design-docs/index.md`、`docs/design-docs/api.md`。
- [x] (2026-07-04 15:40+08:00) 阅读相关设计文档 `docs/design-docs/design.md`、`docs/design-docs/module/file.md`、`docs/design-docs/module/task.md`、`docs/design-docs/module/conversation.md`。
- [x] (2026-07-04 15:50+08:00) 对照当前代码确认缺口：`FileController` 只有包 CRUD 和错误列表；`FileService` 未提供包内图片列表；`TaskQueryService` 只有状态、会话任务列表、下载 handle，缺结果列表与会话级产物聚合；`FilesPage.vue` 仍在前端组合 N+1 请求并本地模拟删除。
- [x] (2026-07-04 16:00+08:00) 决定将工作拆成独立计划，而不是直接散补多个 controller 方法。
- [x] (2026-07-04 17:20+08:00) 实现第一阶段只读接口：素材包图片列表、任务结果列表、会话产物聚合列表。
- [x] (2026-07-04 17:55+08:00) 实现第二阶段变更接口：素材图片删除、产物删除、素材/产物重命名。删除采用软删除，重命名只写展示名。
- [x] (2026-07-04 18:25+08:00) 实现第三阶段下载接口：任意素材/产物选择集合的自定义 ZIP 打包下载。
- [x] (2026-07-04 18:55+08:00) 更新前端 API 调用和 `/files` 页面数据加载逻辑，移除产物侧 N+1 请求链和本地演示删除。
- [x] (2026-07-04 19:20+08:00) 跑后端 Maven 测试、前端类型检查与前端测试，记录验证结果。

## Surprises & Discoveries

- Observation: `docs/design-docs/api.md` 已经明确列出“待实现前端需求接口”，其中 6 项正好对应 `/files` 页面素材与产物管理缺口。
  Evidence: 在 `docs/design-docs/api.md` 搜索 `待实现前端需求接口`、`素材包内图片列表查询`、`会话产物聚合查询` 可定位。

- Observation: 当前前端 `pixflow-web/src/pages/FilesPage.vue` 的产物加载是典型 N+1：先列会话，再列每个会话任务，再列每个任务结果，再逐个结果请求下载 URL。
  Evidence: 在该文件搜索 `loadProducts`、`listConversationTasks`、`listTaskResults`、`getResultDownload`。

- Observation: 当前 file 模块处于入口侧边界，`module/file.md` 原本不负责出口侧产物管理；所以产物聚合与产物删除应落在 task 或 app-level 组合 controller，而不是强行让 file 反向依赖 task。
  Evidence: 在 `docs/design-docs/module/file.md` 搜索 `入口侧`、`出口侧`、`职责边界`、`反向约束`。

- Observation: 产物下载已有 `DownloadService` 和 `DownloadBundleBuilder`，但接口只覆盖单任务全量打包，不能覆盖跨任务、跨素材包、任意选择集合。
  Evidence: 在 `docs/design-docs/api.md` 搜索 `自定义批量下载打包接口`；在 `docs/design-docs/module/task.md` 搜索 `DownloadBundleBuilder`、`下载分发`。

- Observation: task 模块当前不直接承载 web controller，app 模块已经是组合 file 与 task 的合适边界。
  Evidence: `pixflow-app/src/main/java/com/pixflow/app/task/TaskQueryController.java` 暴露任务查询、会话产物聚合、结果删除与重命名路由；`pixflow-app/src/main/java/com/pixflow/app/download/CustomDownloadService.java` 同时读取素材图片和任务结果。

- Observation: 前端请求工具需要统一拆开 `ApiResponse.data`，否则新增接口虽然返回正确业务 payload，页面仍会拿到 envelope 外壳。
  Evidence: `pixflow-web/src/api/client.ts` 已统一解包成功响应，并将后端分页 `records` 兼容映射为前端 `items`。

- Observation: 自定义 ZIP 打包复用了既有 `DownloadBundleBuilder` 的内存构建方式，没有在本计划内进一步改造成真正的大文件流式 ZIP。
  Evidence: `DownloadBundleBuilder` 改为接受通用 `BundleSource`，但仍使用 `ByteArrayOutputStream` 生成临时 ZIP 后写入 TMP bucket。

## Decision Log

- Decision: 第一阶段先实现只读聚合接口，第二阶段再做删除、重命名和自定义打包。
  Rationale: 只读接口不需要修改表结构，能最快消除 `/files` 页面的 mock 与 N+1；删除和重命名涉及事实源语义、软删除字段、对象存储清理和历史任务可回放性，风险更高，应单独验收。
  Date/Author: 2026-07-04 / Codex

- Decision: 素材包内图片接口归 `pixflow-module-file`，产物结果与产物聚合接口归 `pixflow-module-task` 或 app-level controller，不让 file 依赖 task。
  Rationale: `module/file.md` 明确 file 是素材入口侧模块；task 是 `process_result` 的拥有者。保持依赖方向能避免破坏执行计划中的模块 DAG。
  Date/Author: 2026-07-04 / Codex

- Decision: 预览和下载响应只返回 MinIO 预签名 URL，不通过应用层代理图片字节。
  Rationale: `api.md` 与 `task.md` 都强调应用层不得代理大文件，前端直接使用预签名 URL，避免后端阻塞和 OOM 风险。
  Date/Author: 2026-07-04 / Codex

- Decision: 删除语义采用“数据库软删除优先，对象存储延迟或受限清理”，不直接物理删除历史任务仍可能引用的对象。
  Rationale: `design.md` 中 MySQL 是事实源，历史任务和回放必须可解释；直接删除 MinIO 对象会破坏历史预览和 rubrics 证据链。素材侧可沿用 package 删除的软删思路，产物侧需要为 `process_result` 补删除/隐藏语义。
  Date/Author: 2026-07-04 / Codex

- Decision: 任务查询、会话产物聚合和自定义下载 controller 放在 `pixflow-app`，不放进 `pixflow-module-file`。
  Rationale: 自定义下载需要同时解析 `asset_image` 与 `process_result`，放在 app 组合层可以避免 file/task 之间新增反向依赖。task 模块仍只暴露 `TaskQueryService` 等 Java API。
  Date/Author: 2026-07-04 / Codex

- Decision: 全局前端 `request()` 负责解包统一响应 envelope，并兼容后端分页字段 `records` 到前端 `items`。
  Rationale: 后端 controller 统一返回 `ApiResponse`；让每个页面重复处理 envelope 会增加错误面，集中在 API client 处理更符合现有前端分层。
  Date/Author: 2026-07-04 / Codex

- Decision: 本计划不把 `DownloadBundleBuilder` 改造成完全流式 ZIP，只先抽象通用 `BundleSource` 复用既有行为。
  Rationale: 当前需求是让 `/files` 页具备真实自定义打包能力；流式 ZIP 涉及限额、超大文件、临时对象生命周期和错误恢复，适合后续单独优化。
  Date/Author: 2026-07-04 / Codex

## Outcomes & Retrospective

已完成 `/files` 页面后端图片管理接口补齐，并完成前端接入。素材侧新增包内图片列表、单图软删除、单图重命名；产物侧新增任务结果列表、会话产物聚合、结果软删除、结果重命名；下载侧新增 `POST /api/downloads/bundle`，可按前端选中的素材图和产物图生成临时 ZIP 预签名 URL。

后端实现保持模块边界：素材事实源能力在 `pixflow-module-file`，任务结果事实源能力在 `pixflow-module-task`，跨素材与产物的 HTTP 组合放在 `pixflow-app`。删除语义没有删除 MinIO 对象，只设置 `deleted_at`，避免破坏历史任务回放和证据链。重命名只写 `display_name`，不修改 `original_path` 或 `output_minio_key`。

前端 `/files` 已从 mock/N+1 组合改为调用后端聚合接口。素材使用 `listPackages` + `listPackageImages`，产物使用 `listConversations` + `listConversationImages`，删除和批量下载都调用真实后端接口。`request()` 同步修正为解包 `ApiResponse.data`，并兼容分页字段。

验证结果：

    mvn -pl pixflow-module-file -am test
    通过

    mvn -pl pixflow-module-task -am test
    通过

    mvn -pl pixflow-app -am test
    通过

    pnpm typecheck
    通过

    pnpm test
    通过，8 个测试文件，66 个测试

保留的技术债：自定义 ZIP 当前沿用既有内存构建实现，适合当前小批量图片下载；如果后续支持大批量或大尺寸素材，应单独把 `DownloadBundleBuilder` 改为真正流式写入临时对象。

## Context and Orientation

本仓库是 Maven 多模块 Spring Boot 后端加 Vue 3 前端。与本计划直接相关的模块如下。

`pixflow-module-file` 拥有素材包入口侧能力。当前 `pixflow-module-file/src/main/java/com/pixflow/module/file/web/FileController.java` 暴露 `POST /api/files/packages`、`GET /api/files/packages/{id}`、`GET /api/files/packages`、`GET /api/files/packages/{id}/errors`、`DELETE /api/files/packages/{id}`。当前 `FileService` 能上传 zip、查询包、列包、查错误、删包，但没有 `asset_image` 的分页列表、预签名 URL、单图删除、单图重命名。

`pixflow-module-task` 拥有任务和产物事实源。`process_task` 表保存任务，`process_result` 表保存产物图。当前 `TaskQueryService` 提供 `getStatus`、`subscribe`、`listByConversation`、`getResultDownload`，但没有公开的结果分页列表，也没有会话级聚合产物列表。`DownloadService` 已能对单个结果生成预签名 URL，也能对单任务成功结果打包。

`pixflow-conversation` 拥有会话表和确认/取消 REST 边界。会话产物聚合接口若需要按会话校验任务归属，可通过 task 的 `conversation_id` 过滤，不应反向读取 conversation 内部表写接口。

`pixflow-web/src/pages/FilesPage.vue` 是直接受益页面。当前素材侧假设 `PackageDetail` 上有 `images` 字段；产物侧使用多层循环拼接结果；删除按钮只做本地状态删除。这些都是本计划要替换的前端集成点。

本文使用几个术语。素材图片是 `asset_image` 表中的原始图片条目，属于某个 `asset_package`。产物图片是 `process_result` 表中 `status=SUCCESS` 且有 `output_minio_key` 的处理结果。预签名 URL 是对象存储生成的临时访问地址，前端直接用它预览或下载图片，应用后端不转发图片字节。聚合接口是一次请求返回多个图片条目和对应 URL 的接口，用来替代前端循环请求。

## 参考文档与检索关键词

执行本计划前先读以下文档，并用这些关键词定位相关设计依据。

在 `docs/design-docs/api.md` 中搜索：

- `待实现前端需求接口`：定位本计划直接来源。
- `素材包内图片列表查询`：定位 `GET /api/files/packages/{packageId}/images` 的建议。
- `素材单图删除`：定位素材图片删除接口建议。
- `会话产物聚合查询`：定位 `GET /api/conversations/{conversationId}/images` 的建议和 N+1 背景。
- `产物单图删除`：定位 `DELETE /api/tasks/{taskId}/results/{resultId}` 的建议。
- `图片重命名接口`：定位素材和产物重命名需求。
- `自定义批量下载打包接口`：定位 `POST /api/downloads/bundle` 的跨来源打包需求。
- `应用层不得代理结果图片字节`、`预签名下载 URL`：定位下载边界。

在 `docs/design-docs/module/file.md` 中搜索：

- `入口侧 vs 出口侧`、`入口侧聚焦`：理解为什么素材管理属于 file，而产物管理不能强塞进 file。
- `asset_image`、`minio_key`、`original_path`：定位素材图片表字段。
- `素材包删除语义`、`软删优先`：定位删除策略参考。
- `ObjectStorage`、`StorageKeys.packageImage`：定位素材图对象存储 key 约定。
- `反向约束`、`不依赖 module/task`：确认 file 不能依赖 task。

在 `docs/design-docs/module/task.md` 中搜索：

- `TaskQueryService`：定位任务查询服务的既有 API 边界。
- `process_result`：定位产物事实源字段和状态。
- `下载分发`、`DownloadBundleBuilder`、`MinIO Presigned URL`：定位结果下载与打包机制。
- `MySQL 是事实源`：定位删除/重命名不能只动对象存储的原因。
- `反向约束`：确认 task 不依赖 conversation/file 内部实现。

在 `docs/design-docs/module/conversation.md` 中搜索：

- `REST/SSE 端点形态`：确认会话相关路由风格。
- `取消与进度入站`：理解 conversation 与 task 的 web 边界。
- `职责边界与不变量`：确认 conversation 不应拥有产物事实源，只能做 web 边界或调用 task API。

在 `docs/design-docs/design.md` 中搜索：

- `MySQL 是事实源`：确认数据修改以关系表为准。
- `process_result`、`asset_image`：定位全局数据模型。
- `MinIO`、`results/{taskId}`、`packages/{packageId}/images`：定位对象存储布局。
- `异步执行时序`：理解产物生成后如何落库。

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索：

- `Wave 2`、`module/file`：确认素材模块位置。
- `Wave 4`、`module/task`、`module/conversation`：确认任务和会话模块位置。
- `模块依赖 DAG`：确认新增接口不能引入反向依赖。

## Plan of Work

第一步是补只读 DTO 和 file 侧素材图片列表。新增 `AssetImageView`，字段包含 `imageId`、`packageId`、`filename`、`originalPath`、`skuId`、`groupKey`、`viewId`、`size`、`url`、`createdAt`。`filename` 从 `originalPath` 的最后一段派生。`size` 可先通过 `ObjectStorage.stat(ObjectLocation.of(BucketType.PACKAGES, minioKey))` 获取；如果对象存储 stat 失败，应返回 `null` 或 `-1` 并记录指标，不让整页失败。`url` 通过 `ObjectStorage.presignGet(ObjectLocation.of(BucketType.PACKAGES, minioKey), ttl)` 生成。新增 `GET /api/files/packages/{packageId}/images?page=0&size=50`，分页查询 `asset_image`，只返回所属包未删除且存在的图片。

第二步是补 task 侧结果列表。新增 `TaskResultView`，字段包含 `resultId`、`taskId`、`status`、`kind`、`imageId`、`skuId`、`groupKey`、`viewId`、`branchId`、`filename`、`size`、`url`、`createdAt`、`finishedAt`、`errorMsg`。新增 `TaskQueryService.listResults(TaskId, PageQuery)`，controller 暴露 `GET /api/tasks/{taskId}/results?page=0&size=50`。对 `SUCCESS` 且有 `output_minio_key` 的结果生成预签名 URL；失败或跳过结果不生成 URL，但保留错误信息用于前端展示。

第三步是补会话产物聚合。新增 `TaskQueryService.listConversationImages(conversationId, PageQuery)` 或 app-level `ResultGalleryController`，暴露 `GET /api/conversations/{conversationId}/images?page=0&size=100`。实现上用 `process_task.conversation_id` join `process_result.task_id`，过滤成功结果并一次性返回 URL，避免前端按会话任务逐层请求。响应可以按平铺列表返回，也可以附带 `taskId`、`taskStatus`、`conversationId` 让前端自行按会话/任务分组。第一版优先平铺，前端现有“按文件夹”可以按 `conversationId` 或 `taskId` 分组。

第四步是补删除语义。素材图片删除应新增 `asset_image.deleted_at` 或等价 `status` 字段；如果当前迁移体系不允许改旧 schema，就新增 migration。`DELETE /api/files/packages/{packageId}/images/{imageId}` 先校验图片归属，再软删除 `asset_image`，默认列表过滤。物理删除 MinIO 对象只在确认没有任务引用该图片时执行；第一版可以只软删，不删对象，保证历史任务可回放。产物删除同理，给 `process_result` 增 `deleted_at` 或 `visible` 字段，`DELETE /api/tasks/{taskId}/results/{resultId}` 只隐藏结果，不删 MinIO 对象。批量删除可用 `POST /api/files/packages/{packageId}/images:batch-delete` 与 `POST /api/tasks/{taskId}/results:batch-delete`，也可以先让前端并发调用单删；若要减少请求，优先实现批量端点。

第五步是补重命名语义。素材侧不要直接改 `original_path`，因为它是 zip 内原始路径和幂等唯一约束；新增 `display_name` 字段保存用户可见文件名。产物侧新增 `display_name` 字段到 `process_result`，不改 `output_minio_key`，避免对象存储重命名带来的复制和历史 URL 失效。新增 `PATCH /api/files/packages/{packageId}/images/{imageId}` 与 `PATCH /api/tasks/{taskId}/results/{resultId}`，请求体 `{ "displayName": "..." }`。批量统一前缀+序号由前端生成多个目标名后调用批量更新接口，后端只做合法性和唯一性校验。

第六步是补自定义打包下载。新增 `POST /api/downloads/bundle`，请求体包含 `items` 数组，每项形如 `{ "type": "ASSET_IMAGE", "imageId": 123 }` 或 `{ "type": "TASK_RESULT", "resultId": "456" }`，可选 `filename`。后端解析每个 item 到 `ObjectLocation`，用现有 `DownloadBundleBuilder` 思路流式生成临时 ZIP 到 `BucketType.TMP`，再返回 `DownloadHandle{url, expiresAt}`。这个 builder 不能只接受 `ProcessResult`，需要抽象成通用 `BundleSource{name, location, size}`，让 task 旧的单任务 bundle 和新的自定义 bundle 复用同一实现。

第七步是更新前端。`pixflow-web/src/api/packages.ts` 增加 `listPackageImages`、`deletePackageImages`、`renamePackageImage`。`pixflow-web/src/api/tasks.ts` 增加或修正 `listTaskResults` 的真实字段，新增 `listConversationImages`、`deleteTaskResults`、`renameTaskResult`。新增 `pixflow-web/src/api/downloads.ts` 调用 `POST /api/downloads/bundle`。`FilesPage.vue` 的素材加载改为先列包，再并发按包拉图片，或者后端若提供包+图片聚合则直接使用；产物加载改为调用 `listConversationImages`，不再循环 `listConversationTasks/listTaskResults/getResultDownload`。删除按钮改为调用后端批量删除成功后刷新当前列表，移除“仅供前端演示”文案。

## Concrete Steps

从仓库根目录 `D:\study\PixFlow` 开始。

1. 先确认现有工作区改动，避免覆盖他人修改：

       git status --short

   预期会看到当前已有多处未提交改动。执行者只能修改本计划相关文件，不得还原无关改动。

2. 实现素材图片只读接口。编辑：

   - `pixflow-module-file/src/main/java/com/pixflow/module/file/image/AssetImageMapper.java`，新增按 package 分页和按 id+package 查询方法。
   - `pixflow-module-file/src/main/java/com/pixflow/module/file/FileService.java`，新增 `listImages(packageId, page, size)`。
   - `pixflow-module-file/src/main/java/com/pixflow/module/file/web/FileController.java`，新增 `GET /api/files/packages/{id}/images`。
   - 新增 DTO 文件，例如 `pixflow-module-file/src/main/java/com/pixflow/module/file/image/AssetImageView.java`。

3. 实现任务结果只读接口。编辑：

   - `pixflow-module-task/src/main/java/com/pixflow/module/task/api/TaskQueryService.java`，新增 `listResults` 和会话产物聚合方法。
   - `pixflow-module-task/src/main/java/com/pixflow/module/task/internal/query/TaskQueryServiceImpl.java`，实现分页和 URL 生成。
   - `pixflow-module-task/src/main/java/com/pixflow/module/task/infra/persistence/ProcessResultMapper.java`，新增分页查询、join 会话查询。
   - 新增 controller，例如 `pixflow-module-task/src/main/java/com/pixflow/module/task/api/TaskQueryController.java`，暴露 `GET /api/tasks/{taskId}`、`GET /api/tasks/{taskId}/results`、`GET /api/tasks/{taskId}/downloads`、`GET /api/tasks/{taskId}/downloads/bundle`、`GET /api/conversations/{conversationId}/tasks`、`GET /api/conversations/{conversationId}/images`。如果已有 app controller 承担这些路由，则在既有 controller 上扩展。

4. 为删除和重命名补 schema。新增迁移文件，不修改旧迁移：

   - `pixflow-module-file/src/main/resources/db/migration/V2__asset_image_gallery_fields.sql`，给 `asset_image` 增 `display_name`、`deleted_at`，并加查询索引。
   - `pixflow-module-task/src/main/resources/db/migration/V2__process_result_gallery_fields.sql`，给 `process_result` 增 `display_name`、`deleted_at`，并加查询索引。

   SQL 应幂等或符合项目 Flyway 风格。已有 `schema.sql` 若用于测试初始化，也同步补字段。

5. 实现素材删除/重命名和产物删除/重命名。优先实现软删除，列表接口默认过滤 `deleted_at is null`。如果前端需要批量操作，新增批量 request DTO；如果先做单项接口，前端可以并发调用。

6. 抽象通用打包下载。编辑：

   - `pixflow-module-task/src/main/java/com/pixflow/module/task/internal/download/DownloadBundleBuilder.java`，抽出 `BundleSource` 输入，不再只绑定 `ProcessResult`。
   - 新增通用下载 controller 或 service，位置可放在 `pixflow-module-task` 中，因为现有下载池、限额、临时桶配置都在 task 模块；若需要跨 file 解析素材图片，用只读 port 注入，而不是让 file 依赖 task。

7. 更新前端 API 和页面：

   - `pixflow-web/src/api/packages.ts`
   - `pixflow-web/src/api/tasks.ts`
   - `pixflow-web/src/api/downloads.ts`（新建）
   - `pixflow-web/src/pages/FilesPage.vue`
   - 必要时更新 `pixflow-web/src/types/upload.ts`、`pixflow-web/src/types/api.ts`

8. 更新 `docs/design-docs/api.md`，把已落地接口从“待实现前端需求接口”移动或标注为“已采用路由”，记录最终请求/响应字段。若实现中因模块边界调整了推荐路径，必须在该文档解释原因。

## Validation and Acceptance

后端单元测试和模块测试：

1. 跑 file 模块测试：

       mvn -pl pixflow-module-file -am test

   预期新增测试通过：素材包图片列表只返回未删除图片；图片 URL 非空；不存在的 package 或 image 返回规范错误；重命名只改 `display_name` 不改 `original_path`。

2. 跑 task 模块测试：

       mvn -pl pixflow-module-task -am test

   预期新增测试通过：任务结果分页返回成功/失败/跳过的正确字段；成功结果带 URL；会话聚合接口只返回该会话任务的成功产物；删除后的结果不再出现在默认列表；单任务 bundle 旧行为保持不变；自定义 bundle 能同时打包素材图和产物图。

3. 如果新增 app-level controller，跑 app 相关测试：

       mvn -pl pixflow-app -am test

   预期 Spring context 能启动，路由无冲突。

前端验证：

1. 跑类型检查：

       pnpm typecheck

   预期无 TypeScript 错误，`FilesPage.vue` 不再引用不存在的 `pkg.images` mock 字段。

2. 跑前端测试：

       pnpm test

   预期现有测试通过；若新增 API adapter 测试，断言路径和 query 参数与 `api.md` 一致。

手工验收：

1. 启动后端和前端，上传一个包含多张图片的 zip，进入 `/files` 的“素材”页。应看到素材包内图片，图片可以预览，浏览器网络面板中每个素材包最多一次图片列表请求，不再依赖 mock。

2. 执行一个生成产物的任务后进入 `/files` 的“产物”页。应看到该会话下产物图片，浏览器网络面板不再出现“每个 result 再请求一次 downloads”的 N+1 链路。

3. 勾选多张素材或产物后删除。刷新页面后这些条目仍不可见；数据库记录保留 `deleted_at`，对象存储对象没有被误删。

4. 重命名单张图片。刷新页面后展示名保持新名称；原始 `original_path` 和 `output_minio_key` 不改变。

5. 勾选跨素材包和跨任务的图片执行批量下载。后端返回 ZIP 的预签名 URL，下载的 zip 中包含所有选中图片，文件名使用 `display_name` 或 fallback 名称。

## Idempotence and Recovery

只读接口可以重复调用，不产生副作用。预签名 URL 每次调用可能不同，但指向同一对象。

删除接口必须幂等。对已经软删除的素材图片或产物再次删除，应返回成功或明确的 no-op，不应抛 500。删除只设置 `deleted_at`，不直接删除对象存储字节，因此误操作可通过数据库恢复字段进行人工恢复。

重命名接口必须只改 `display_name`。如果对象存储 key 不变，恢复很简单：把 `display_name` 改回旧值或置空即可回退到 `original_path` / result fallback 名称。

自定义打包下载生成的是临时 ZIP。若打包中断，临时对象可以由 TTL 清理或后续清理任务删除；重复请求会生成新的临时 ZIP，不影响源图片和产物。

新增 migration 不修改旧 migration 文件，避免破坏已经初始化过的开发库。测试用 `schema.sql` 可以同步补字段，但生产迁移必须走新增版本文件。

## Artifacts and Notes

当前代码观察到的关键事实：

    pixflow-module-file/src/main/java/com/pixflow/module/file/web/FileController.java
    已有路由:
      POST /api/files/packages
      GET /api/files/packages/{id}
      GET /api/files/packages
      GET /api/files/packages/{id}/errors
      DELETE /api/files/packages/{id}
    缺:
      GET /api/files/packages/{id}/images
      DELETE /api/files/packages/{id}/images/{imageId}
      PATCH /api/files/packages/{id}/images/{imageId}

    pixflow-module-task/src/main/java/com/pixflow/module/task/api/TaskQueryService.java
    已有能力:
      getStatus
      subscribe
      listByConversation
      getResultDownload
    缺:
      listResults
      listConversationImages
      deleteResult / batchDeleteResults
      renameResult
      custom bundle by arbitrary selected items

    pixflow-web/src/pages/FilesPage.vue
    当前素材侧读取 pkg.images，但后端 PackageDetail 不提供 images。
    当前产物侧在 loadProducts 中循环 listConversations -> listConversationTasks -> listTaskResults -> getResultDownload。

## Interfaces and Dependencies

建议最终 HTTP 接口如下。

素材只读：

    GET /api/files/packages/{packageId}/images?page=0&size=50

响应 payload：

    {
      "items": [
        {
          "imageId": "123",
          "packageId": 10,
          "filename": "front.png",
          "displayName": null,
          "originalPath": "sku001/front.png",
          "skuId": "sku001",
          "groupKey": null,
          "viewId": "front",
          "size": 123456,
          "url": "https://...",
          "createdAt": "2026-07-04T08:00:00Z"
        }
      ],
      "total": 1,
      "page": 0,
      "size": 50
    }

产物只读：

    GET /api/tasks/{taskId}/results?page=0&size=50
    GET /api/conversations/{conversationId}/images?page=0&size=100

产物条目 payload：

    {
      "resultId": "456",
      "taskId": "99",
      "conversationId": "conv-1",
      "status": "SUCCESS",
      "kind": "BRANCH",
      "imageId": "123",
      "skuId": "sku001",
      "groupKey": null,
      "viewId": "front",
      "branchId": "branch-main",
      "filename": "sku001_branch-main.png",
      "displayName": null,
      "size": 223344,
      "url": "https://...",
      "createdAt": "2026-07-04T08:01:00Z",
      "finishedAt": "2026-07-04T08:02:00Z"
    }

删除：

    DELETE /api/files/packages/{packageId}/images/{imageId}
    DELETE /api/tasks/{taskId}/results/{resultId}

批量删除可选：

    POST /api/files/packages/{packageId}/images:batch-delete
    POST /api/tasks/{taskId}/results:batch-delete

重命名：

    PATCH /api/files/packages/{packageId}/images/{imageId}
    PATCH /api/tasks/{taskId}/results/{resultId}

请求 payload：

    { "displayName": "new-name.png" }

自定义打包：

    POST /api/downloads/bundle

请求 payload：

    {
      "items": [
        { "type": "ASSET_IMAGE", "imageId": "123" },
        { "type": "TASK_RESULT", "resultId": "456" }
      ],
      "archiveName": "selected-images.zip"
    }

响应 payload：

    {
      "url": "https://...",
      "expiresAt": "2026-07-04T08:30:00Z"
    }

模块依赖约束：

`pixflow-module-file` 可以依赖 `infra/storage`、`common`、MyBatis，不依赖 `pixflow-module-task`。`pixflow-module-task` 可以读自己的 `process_task` / `process_result`，并使用 `infra/storage` 生成预签名 URL。若自定义打包需要解析素材图片，使用一个由 file 提供的只读 port，例如 `AssetImageReadPort`，由 task 或 app 注入接口，不让 file 反向依赖 task。`pixflow-web` 只调用 HTTP API，不拼对象存储 key。

## Revision Notes

2026-07-04 / Codex: 新建计划。原因是 `api.md` 中列出的 `/files` 页面接口缺口横跨 file、task、conversation/app controller、storage 和前端，复杂度超过直接补 controller 方法；需要先固定机制、模块边界、接口形态和验证方式。

2026-07-04 / Codex: 完成实现并更新计划状态。实际落地时将 task HTTP 查询和跨来源自定义下载放在 app 组合层；新增 file/task V2 migration 补 `display_name` 与 `deleted_at`；前端统一解包 `ApiResponse` 后切换 `/files` 页面到真实后端接口。

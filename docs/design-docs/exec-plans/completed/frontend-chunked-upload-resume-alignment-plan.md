# 统一前端分片上传与后端断点续传协议

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。执行者只依赖当前工作树和本文，就应能把 `pixflow-web` 的素材包分片上传实现与 `pixflow-module-file` 已落地的上传协议统一起来。实施期间每个停止点都必须更新本文；改变协议、状态机、依赖、里程碑顺序或验收方式时，必须同步更新 `Progress`、`Decision Log` 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，用户可以上传大体积 zip，在网络中断、页面刷新、浏览器退出或主动暂停后重新选择同一文件，并继续使用后端仍然有效的上传会话。前端只会 PUT 后端尚未确认的分片，不会把恢复响应误判成冲突，也不会因为错误的整包 hash 在 `complete` 阶段必然收到 `FILE_HASH_MISMATCH`。用户主动取消才会删除后端会话；暂停、断网和刷新只停止当前请求并保留已上传分片。

可观察的最终行为是：同一文件首次上传若已完成分片 0、1、2，刷新页面后重新选择该文件，`POST /api/files/packages/init` 返回 `mode=RESUME` 和原 `uploadId`，浏览器 Network 面板只出现从缺失编号开始的 PUT；全部分片完成后 `POST /complete` 返回 `packageId` 和 `UPLOADED`。前端单元测试还会用标准 SHA-256 已知向量证明 `fileHash` 等于完整文件原始字节的摘要，而不是分片摘要树。

## Progress

- [x] (2026-07-13 12:23+08:00) 阅读 `PLANS.md`、当前 `docs/design-docs/exec-plans/` 执行计划和 `docs/design-docs/index.md`，确认当前阶段没有正在实施的上传计划；现有 execution-domain 计划的前端里程碑只涉及任务体验，不与本计划冲突。
- [x] (2026-07-13 12:23+08:00) 阅读上传相关前端与后端设计：`docs/design-docs/frontend/api.md`、`docs/design-docs/web.md`、`docs/design-docs/frontend/upload.md`、`docs/design-docs/frontend/transport-api.md`、`docs/design-docs/module/file.md`、`docs/design-docs/infra/cache.md`、`docs/design-docs/infra/storage.md`。
- [x] (2026-07-13 12:23+08:00) 核对前端上传源码、后端真实 record/service/config/error code 和现有测试，记录跨端差距并创建本计划。
- [x] (2026-07-13 12:58+08:00) Milestone 1：统一现行上传设计与配置前缀；前端以 discriminated union 固定 `UPLOAD | RESUME | DEDUP`，新增 7 项 API 契约测试，后端 service 契约补齐 READY DEDUP、RESUME、重复分片和终止会话错误断言。
- [x] (2026-07-13 12:58+08:00) Milestone 2：引入 `@noble/hashes`，新增 4 MiB 增量核心与专用 Web Worker；空文件、`abc` 和跨块 oracle 测试通过，Vite 成功产出独立 worker bundle。
- [x] (2026-07-13 12:58+08:00) Milestone 3：`runChunkPool` 改为显式接收 server `expectedChunks/initialUploadedIndices`，只调度差集，按真实 slice 字节计进度，并仅重试网络/5xx；uploadJob 覆盖 UPLOAD、RESUME、DEDUP、READY、过期有界 re-init 和 complete 失败恢复。
- [x] (2026-07-13 12:58+08:00) Milestone 4：增加 `paused`、generation guard 和 `pause/resume/retry/cancel`；Pinia 保留 paused/error 卡片，UI 提供中文暂停、继续、重试和取消操作，暂停不 DELETE、取消立即更新 UI 后 best-effort DELETE。
- [ ] (2026-07-13 13:09+08:00) Milestone 5（已完成自动部分）：前端 24 files / 114 tests、typecheck、build，module/file 32 tests，30 模块 reactor install/compile 均通过。未完成：用户明确要求跳过浏览器验证；应用又被 execution-domain 工作树中 `TaskCommandService` Bean 缺失阻断，无法执行真实 HTTP 刷新/断网续传。

## Surprises & Discoveries

- Observation: 当前 `sha256File()` 计算的是 `SHA256(hex(SHA256(block1)) + ...)`，后端 `complete()` 计算的是 `SHA256(fileBytes)`；两者对非空文件通常不同，因此现有浏览器分片上传无法通过整包校验。
  Evidence: `pixflow-web/src/upload/hasher.ts` 的 `hexParts` 和第二层 `digestHex`，对照 `docs/design-docs/frontend/api.md` 的“`fileHash` 的严格定义”以及 `UploadSessionService.writeComposedObject(...)`。

- Observation: 前端虽然在上传前调用 `getSession(uploadId)`，却没有保存或传递返回的 `uploadedChunks`；`runChunkPool()` 每次仍把 `0..expectedChunks-1` 全部加入队列。
  Evidence: `pixflow-web/src/upload/uploadJob.ts` 只检查 session 终态，`pixflow-web/src/upload/chunkWorker.ts` 无初始已上传集合参数。

- Observation: 前端类型仍把 active session 建模为 `DEDUP + UPLOADING` 并转成 409 错误，而后端已经返回独立的成功模式 `RESUME`。
  Evidence: `pixflow-web/src/types/upload.ts#InitUploadResponse` 与 `uploadJob.ts` 的 DEDUP 分支，对照 `pixflow-module-file/.../InitUploadResponse.java#resume` 和 `UploadSessionServiceTest.resumesActiveSessionByFileHash`。

- Observation: 本地 session 已写入 `localStorage`，但 `uploadJob.run()` 不读取它；Pinia 的 `activeJobs` 又排除了 `error` 和 `cancelled`，所以 UI 注释所说的失败重试入口实际上不可见。
  Evidence: `resumeLocalSession()` 没有生产调用，`stores/uploadJobs.ts#activeJobs` 过滤终态，而 `PackageUploader.vue` 只遍历 `activeJobs`。

- Observation: 上传设计文档内部存在漂移。`frontend/api.md` 明确上传端没有 429，`UPLOAD_SESSION_NOT_UPLOADING` 不可重试；`web.md §16.5/16.6` 仍描述 429 和 409 退避；`frontend/upload.md` 甚至称保留 429 分支“无害”。
  Evidence: 搜索 `没有令牌桶限流`、`UPLOAD_SESSION_NOT_UPLOADING` 和 `429` 可并列看到冲突。

- Observation: 后端配置类的真实前缀是 `pixflow.file`，上传配置位于嵌套的 `upload` 对象；部分文档写成了 `pixflow.upload.*`。
  Evidence: `pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileProperties.java` 的 `@ConfigurationProperties(prefix = "pixflow.file")`。

- Observation: 现有前端测试只有单分片 `sha256Blob`、切片和 localStorage 测试，没有 `sha256File` 标准向量、worker 池或 `uploadJob` 编排测试，因而没有捕获上述跨端缺陷。
  Evidence: `pixflow-web/src/upload/__tests__/` 当前只有 `hasher.test.ts`、`chunker.test.ts` 和 `sessionStore.test.ts`。

- Observation: 从 reactor 根执行 `mvn -pl pixflow-app -am spring-boot:run` 会把 Spring Boot goal 先应用到根 POM，并因根模块没有 main class 失败；可运行方式是先 reactor install，再直接启动 `pixflow-app` 的可执行 jar。
  Evidence: `.scratch/upload-app.log` 首次启动报 `Unable to find a suitable main class`；`mvn -pl pixflow-app -am -DskipTests install` 随后 30 模块成功并生成可执行 jar。

- Observation: 当前完整应用仍无法进入 HTTP 就绪态，阻断来自并行 execution-domain 改造而非上传模块。
  Evidence: 可执行 jar 连接 MySQL/Redis 成功后，Spring 报 `TaskQueryController` 缺少 `TaskCommandService` Bean；module/file 32 tests 与前端 114 tests 独立通过。

- Observation: 首轮实现虽然用 generation guard 防止迟到响应覆盖 UI，但若 `AbortSignal` 不传入 API adapter，暂停仍不能真正终止在飞 fetch；同时 Pinia 只复制 phase/progress 会让卡片的 hash、uploadId 和分片计数陈旧。
  Evidence: 双轴 code review 指出 `putChunk/init/getSession/complete` 缺 signal 与 store 双事实源；修复后 signal 贯穿全部上传请求，新增在飞 PUT abort 测试，Pinia 每次复制完整 handle state，复核无剩余代码/计划问题。

## Decision Log

- Decision: 后端 `InitUploadResponse` 和 `UploadSessionState` 是上传恢复的权威事实；localStorage 只保存恢复提示，不决定哪些分片已完成。
  Rationale: 后端 Redis/MinIO 联合快照才能证明分片真实存在；浏览器缓存可能过期、被清理或来自另一标签页。
  Date/Author: 2026-07-13 / Codex

- Decision: 整包 hash 使用成熟的增量 SHA-256 实现，并在专用 Web Worker 中连续 `update` 文件原始字节；单个 5 MiB 分片仍可使用 Web Crypto `subtle.digest`。
  Rationale: Web Crypto 没有流式 digest API，把完整 2 GiB 文件读入内存不可接受，而自行实现 SHA-256 风险高。Web Worker 避免长时间阻塞 Vue 主线程。
  Date/Author: 2026-07-13 / Codex

- Decision: 实施时给 `pixflow-web` 增加 `@noble/hashes` 直接依赖，在 worker 中使用 `sha256.create().update(...)`，不引入 Uppy、tus 或新的上传框架。
  Rationale: `@noble/hashes` 提供可增量更新、Vite/ESM 兼容的窄接口；现有自定义 `init/chunk/complete` 协议继续由本项目控制。
  Date/Author: 2026-07-13 / Codex

- Decision: 保留当前客户端每分片最多 5 次 attempt 和 60 秒累计退避预算，只允许网络错误和 5xx 重试；移除 429 与 `UPLOAD_SESSION_NOT_UPLOADING` 的重试分支。
  Rationale: 重试次数是客户端策略，不需要为协议统一顺带改变；错误分类必须服从后端真实能力。409 表示会话已终止，重复 PUT 不会恢复它。
  Date/Author: 2026-07-13 / Codex

- Decision: 增加显式 `paused` 状态和 `pause()` 操作。暂停仅 abort，不 DELETE；取消先立即更新 UI 并清本地提示，再 best-effort 调 DELETE。
  Rationale: 断点续传只有在暂停与取消语义分离时才可靠。浏览器刷新等价于暂停，不能触发服务端清理。
  Date/Author: 2026-07-13 / Codex

- Decision: 不把 File/Blob 或分片字节写入 IndexedDB。页面刷新后用户需要重新选择同一文件，前端重新计算标准 hash，再由 init 找回会话。
  Rationale: 这符合现有设计边界，避免大文件的第二份浏览器存储和权限/配额问题；fileHash 能验证重新选择的文件身份。
  Date/Author: 2026-07-13 / Codex

- Decision: Milestone 5 保持未完成，不以自动测试替代浏览器刷新/断网续传证据。
  Rationale: 用户明确要求跳过浏览器验证，且应用存在无关启动阻断；计划验收要求对未执行的真实场景如实记录。
  Date/Author: 2026-07-13 / Codex

## Outcomes & Retrospective

Milestone 1-4 已完成。标准整文件 SHA-256、server snapshot 差集 PUT、网络/5xx 有界重试、AbortSignal 贯穿 init/GET/PUT/complete、暂停/继续/重试/取消和 generation 防迟到提交均已实现；前端 114 项测试、typecheck、Vite build、module/file 32 项测试及 30 模块跳过测试的 reactor install/compile 通过。Docker 依赖可启动，应用也能连接 MySQL/Redis，但被当前 execution-domain 改造中的 `TaskCommandService` Bean 缺失阻断。按用户要求未执行浏览器验证，因此刷新、断网、真实非连续分片续传和 Network 面板证据仍未验收，完整计划不标记完成。

## Context and Orientation

PixFlow 前端位于 `pixflow-web/`，使用 Vue 3、Pinia、Vite、fetch 和 Vitest。素材包上传的页面入口经 `components/upload/PackageUploader.vue` 调用 `stores/uploadJobs.ts`，store 创建 `upload/uploadJob.ts` 状态机；状态机通过 `api/packages.ts` 调后端，通过 `hasher.ts` 算整包和分片 hash，通过 `chunkWorker.ts` 并发 PUT，通过 `sessionStore.ts` 保存少量 localStorage 元数据。页面不得自己串联这些 API。

后端上传实现位于 `pixflow-module-file/src/main/java/com/pixflow/module/file/upload/`，HTTP 入口是 `.../web/FileController.java`。一次上传会话以 `uploadId` 标识，后端把 session、已落 MinIO 的分片元数据和 `fileHash -> uploadId` 索引存在 Redis，默认 TTL 24 小时；完整 zip 只有在 complete 后才成为 `asset_package`。`uploadedChunks` 是后端确认已经落盘且可用于 complete 的分片编号集合。

协议有三种 init 成功模式。`UPLOAD` 创建新会话；`RESUME` 返回同 fileHash 的 active 会话及已上传编号；`DEDUP` 表示 READY 素材包已经存在，前端直接得到 `packageId`。`ALREADY_EXISTS` 是重复 PUT 的成功结果，不是错误。后端默认要求固定 5 MiB 分片，前端默认 4 个 worker；不同 `uploadId` 之间不复用物理分片。

“暂停”是停止浏览器当前工作但保留后端会话；“取消”是用户明确放弃并调用 DELETE 清理；“失败重试”是在同一 File 仍可访问时重新 init，让后端决定 RESUME 或新建；“断点续传”不是相信浏览器记录，而是根据 init/GET 返回的后端集合计算全集与已上传集合的差集。

当前两个活跃执行计划分别处理 execution domain 与 rubrics，不改变文件上传协议。本计划只触达上传设计、`pixflow-web` 上传模块、必要的后端上传契约测试和相应文档，不顺带重构 Files 页面、对象存储 adapter 或 Redis `UploadSessionStore`。

## Reference Search Keywords

实施前可用下面的文档路径和关键词快速回到权威上下文。优先使用 `rg -n "关键词" <路径>`，不要依赖旧的 completed plan 作为现行协议来源。

- 在 `docs/design-docs/frontend/api.md` 搜 `分片上传协议`、`RESUME`、`fileHash 的严格定义`、`ALREADY_EXISTS`、`没有令牌桶限流`、`上传会话存储与生命周期`、`去重 / 锁 / 限流总览`，可快速找到浏览器与 controller 的字段、响应、错误和生命周期契约。
- 在 `docs/design-docs/web.md` 搜 `分片上传（核心）`、`标准整文件 SHA-256`、`续传定位`、`分片状态机`、`暂停`、`取消与清理`，可找到前端总体机制和用户交互不变量。实施 Milestone 1 后，本文中的 429/409 旧表述也应已被修正。
- 在 `docs/design-docs/frontend/upload.md` 搜 `Init 响应目标契约`、`Worker 池`、`续传`、`failedChunks`、`暂停与取消必须分离`、`约束`，可找到上传模块的文件边界和摘要契约。
- 在 `docs/design-docs/module/file.md` 搜 `Redis（上传会话）`、`UploadSessionStore 深模块`、`分片上传与整包去重`、`断点续传与取消`、`分片完整性`、`测试策略`，可找到后端事实源、幂等与模块责任。
- 在 `docs/design-docs/infra/cache.md` 搜 `ExpiringStateStore`、`ExpiringHashStore`、`module/file`、`fail-closed`，可找到 Redis KV/Hash、TTL 和故障语义；前端不应越过 module/file 使用这些设施。
- 在 `docs/design-docs/infra/storage.md` 搜 `tmp-uploads`、`composeObject`、`uploadId`、`临时分片`，可找到 MinIO 临时对象与 complete 后清理规则。
- 在 `docs/design-docs/exec-plans/completed/upload-session-store-refactor-plan.md` 搜 `RESUME`、`HKEYS`、`TTL`、`ALREADY_EXISTS`，只能用于理解后端改造历史和既有测试证据；若与上述当前设计冲突，以上述当前设计和真实后端 record/service 为准。

## Plan of Work

### Milestone 1: 固定跨端契约并清理设计漂移

先更新 `docs/design-docs/web.md §十五/§十六` 和 `docs/design-docs/frontend/upload.md`，使它们与 `docs/design-docs/frontend/api.md` 及真实 `FileErrorCode` 一致。init 只保留 `UPLOAD | RESUME | DEDUP` 三种成功模式；`RESUME` 是正常路径；上传分片不按 429 或 `Retry-After` 重试；`UPLOAD_SESSION_NOT_FOUND` 和 `UPLOAD_SESSION_NOT_UPLOADING` 都是当前会话不可继续的终止信号；整包校验码是 `FILE_HASH_MISMATCH`。把上传配置示例统一为 `pixflow.file.upload.chunk-size`、`pixflow.file.upload.session-ttl` 和 `pixflow.file.upload.max-zip-size`，以 `FileProperties` 为准。

随后在 `pixflow-web/src/types/upload.ts` 把 `InitUploadResponse` 改成严格的 discriminated union：`UPLOAD` 和 `RESUME` 都必须带非空 `uploadId/chunkSize/expectedChunks/uploadedChunks`，其中 RESUME 还带 `status: 'UPLOADING'`；`DEDUP` 必须带 `packageId` 和 `status: 'READY'`。`UploadSessionState` 的九个字段与后端 record 对齐，不把 long/必填字段写成 optional。`api/packages.ts` 继续保持薄 adapter，不在其中实现恢复状态机。

新增 `pixflow-web/src/api/__tests__/packages.upload.contract.test.ts`，用真实后端 record 的 JSON 形状固定三种 init、session、ACCEPTED/ALREADY_EXISTS、complete 和 cancel 响应。扩充 `pixflow-module-file/src/test/.../UploadSessionServiceTest.java`，明确断言 active session 返回 `RESUME` 而不是 DEDUP、READY 包才返回 DEDUP、重复分片返回 ALREADY_EXISTS、会话终止后 PUT 返回对应错误。若 controller 层已有统一 MockMvc 测试基座，再增加序列化断言；不要为单个测试新建第二套 Spring 启动框架。

完成本里程碑后，搜索 `DEDUP.*UPLOADING|UPLOAD_RATE_LIMITED|err.status === 429` 在前端上传模块和现行上传设计中应无命中；其他业务（例如 auth 或 AI provider）的 429 不在本计划范围内。

### Milestone 2: 实现标准、增量且可取消的整包 SHA-256

在 `pixflow-web/package.json` 增加 `@noble/hashes`，由 pnpm 更新 lockfile。把 hash 核心拆成一个不依赖 Vue 的增量函数和一个专用 worker，例如 `src/upload/incrementalSha256.ts` 与 `src/upload/fileHash.worker.ts`。worker 接收 File，按 4 MiB slice 读取原始字节，对同一个 `sha256.create()` 实例依次 `update(new Uint8Array(buffer))`，结束后返回小写 64 位 hex；每处理一块发送 `{hashed,total}`。不得对每块 finalize，也不得把 hex 文本喂回第二层 digest。

`hasher.ts#sha256File` 只负责任务创建、进度转发、结果和错误归一化，并接收 AbortSignal。暂停或取消时 terminate worker 并以可识别的 abort 结果结束，旧 worker 的迟到消息不得更新新一代 upload run。`sha256Blob` 继续通过 Web Crypto 一次 digest 当前 5 MiB 分片，因为该内存上界固定且摘要与后端 `X-Chunk-Hash` 定义一致。

扩充 `hasher.test.ts`：覆盖空文件标准摘要、`abc` 已知向量、跨越 4 MiB 边界的多块输入，并用 Node `createHash('sha256')` 在测试环境对同一字节串生成 oracle。单测直接验证增量核心；`pnpm build` 验证 worker URL 和 ESM 依赖能被 Vite 打包。验收时浏览器计算 100 MiB 以上文件 hash，页面输入和取消按钮仍可响应。

### Milestone 3: 以后端快照驱动缺失分片队列

重构 `uploadJob.ts` 的 init 分支。算出 fileHash 后可读取 `sessionStore` 作为恢复提示，但始终调用 init；DEDUP 直接 done，UPLOAD 使用空集合，RESUME 接管后端返回的 uploadId 和 uploadedChunks。对 RESUME 再 GET 一次 session 校准：READY 直接 done，CANCELLED/EXPIRED 清本地提示并进行一次有界 re-init，UPLOADING 使用 GET 返回的集合。GET 若只是网络/5xx 失败，可退回到同一次 init 返回的集合，因为重复 PUT 由后端幂等保护；404/409 不得被吞掉后继续向陈旧 uploadId 写入。禁止无上限递归 init。

修改 `runChunkPool()` 的接口，显式接收 `expectedChunks` 和 `initialUploadedIndices`。构造队列时先校验并去重后端编号，只把 `[0, expectedChunks)` 与已上传集合的差集入队。worker 共享一个 `Set<number>`；每次 ACCEPTED 或 ALREADY_EXISTS 后，用响应 `uploadedChunks` 合并并校准集合，再把不可变快照通知 uploadJob。进度字节数按每个已上传 index 的真实 slice 长度求和，不能用 `count * chunkSize` 近似最后一个分片，也不能因为稀疏编号重复计数。

worker 的可恢复错误只包括 transport network error 与 HTTP 5xx，沿用最多 5 次 attempt、指数退避和 60 秒预算。`CHUNK_HASH_MISMATCH`、`CHUNK_SIZE_MISMATCH`、`CHUNK_OUT_OF_RANGE`、`UPLOAD_SESSION_NOT_FOUND`、`UPLOAD_SESSION_NOT_UPLOADING` 和其他 4xx 立即终止当前 job；409 会话终止时停止所有 worker 继续领取队列。Abort 只返回 aborted，不擅自决定 paused 还是 cancelled。全部缺失分片成功后才进入 complete；complete 失败时保留 server session 和 localStorage，用户重试将重新 init/RESUME，而不是 DELETE。

新增 `chunkWorker.test.ts`，覆盖初始 `[0,2]` 时只 PUT `[1,3]`、ALREADY_EXISTS 成功、并发不超过 4、响应快照校准、最后一片进度、网络/5xx 重试、429 不重试、409 不重试、abort 不再调度和 retry budget。新增 `uploadJob.test.ts`，覆盖 UPLOAD 全流程、RESUME 只传差集、DEDUP 直接完成、GET READY、过期后有界 re-init、complete 失败保留 session 和 retry 后恢复。

### Milestone 4: 分离暂停、重试和取消的用户生命周期

在 `types/upload.ts` 增加 `paused`，在 `UploadJobHandle` 增加 `pause()` 和 `resume()`。每次 run 使用递增 generation 或 run token，只有当前 generation 能提交 phase/error/progress，防止旧 fetch/worker 在 pause/cancel 后把状态覆盖为 error。页内 resume 可复用同一个不可变 File 和已算出的标准 fileHash，但仍须重新 init 取得后端最新事实；刷新后重新选择文件则重新计算 hash。

`pause()` 立即 abort hash worker和所有 PUT，保留 localStorage，不调 DELETE，把 UI 置 paused。`cancel()` 立即 abort、清 localStorage、置 cancelled，然后 best-effort 调 DELETE；不等待 DELETE 才更新界面。收到后端 `UPLOAD_SESSION_NOT_UPLOADING` 时，把当前 job 收敛为 cancelled 或明确的 session-ended 错误，不自动重试同一 uploadId。普通网络/5xx 重试耗尽进入 error，保留恢复信息。

修改 `stores/uploadJobs.ts`，使 paused/error job 仍在上传列表可见，并暴露 pause/resume/retry/cancel；避免 store 的进度 patch 丢失 phase。修改 `UploadJobCard.vue` 使用现有 lucide 图标按钮展示暂停、继续、重试和取消，按钮可用性严格按 phase 决定。`PackageUploader.vue` 继续只调用 store，不直接调用 API。页面 unload 不得调用 DELETE；浏览器自然中断即暂停语义。

完成本里程碑后，用户可在 uploading 点暂停，Network 请求停止且后端 session 仍存在；点继续后先 init/RESUME 再只传差集。点取消后 UI 立即显示已取消，随后 GET 该 uploadId 应为不存在或终止状态。

### Milestone 5: 自动回归与真实断点续传验收

先运行前端定向测试，再跑全量 typecheck/test/build。运行后端 module/file 测试，确认前端改动没有推动后端协议变更。最后用 Docker Compose 启动 Redis、MinIO、MySQL、RocketMQ 等仓库依赖，以 reactor 方式启动 `pixflow-app`，再启动 Vite。若当前 Compose 服务名或 profile 与 README 不同，以根目录 `docker-compose.yml` 和 `README.md` 为准更新本节，不凭空添加第二套启动脚本。

在浏览器选择一个至少跨 6 个 5 MiB 分片的有效 zip。等三个非连续或连续分片成功后暂停或刷新，记录 uploadId 和后端 `uploadedChunks`；重新选择同一 File，观察 init 返回 RESUME 和相同 uploadId，Network 中已成功 index 没有新的 PUT。再做一次断网后恢复、一次页面内 pause/resume、一次用户 cancel。complete 后应返回 UPLOADED 并能查询 package 解压进度。用不同内容但同文件名的 zip 验证不会错误复用；用相同内容的第二次上传验证 DEDUP/READY 不 PUT 分片。

将实际命令、测试数量、Network/响应摘要和未执行项写回 `Progress`、`Surprises & Discoveries` 与 `Outcomes & Retrospective`。只有标准 hash、差集 PUT、暂停/取消和真实刷新恢复四项都有证据时，才能把计划标记完成。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。研究或实施前先用以下命令快速恢复上下文：

    rg -n "分片上传协议|RESUME|fileHash 的严格定义|没有令牌桶限流|上传会话存储与生命周期" docs/design-docs/frontend/api.md
    rg -n "分片上传（核心）|续传定位|暂停|取消与清理|429" docs/design-docs/web.md docs/design-docs/frontend/upload.md
    rg -n "Redis（上传会话）|UploadSessionStore 深模块|断点续传与取消|分片完整性" docs/design-docs/module/file.md
    rg -n "hexParts|mode === 'DEDUP'|uploadedChunks|err.status === 429|activeJobs|resumeLocalSession" pixflow-web/src

安装新增前端依赖并更新 lockfile：

    pnpm --dir pixflow-web add @noble/hashes

实施时先跑定向测试：

    pnpm --dir pixflow-web test -- src/upload/__tests__/hasher.test.ts src/upload/__tests__/chunkWorker.test.ts src/upload/__tests__/uploadJob.test.ts src/api/__tests__/packages.upload.contract.test.ts
    mvn -pl pixflow-module-file -am -Dtest=UploadSessionServiceTest,UploadSessionStoreContractTest -Dsurefire.failIfNoSpecifiedTests=false test

然后跑完整前端和后端回归：

    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    mvn -pl pixflow-module-file -am test

真实联调按仓库现有方式启动：

    docker compose up -d
    mvn -pl pixflow-app -am -DskipTests install
    java -jar pixflow-app/target/pixflow-app-1.0.0-SNAPSHOT.jar
    pnpm --dir pixflow-web run dev

预期前端默认地址由 Vite 输出，后端默认 `http://localhost:8080`。若端口被占用，使用 Vite 输出的新端口；不要硬编码测试到另一个未记录地址。

实施结束前运行漂移检查：

    rg -n "SHA256\(hex|hexParts|DEDUP.*UPLOADING|UPLOAD_RATE_LIMITED|err\.status === 429|RETRYABLE_CODES.*UPLOAD_SESSION_NOT_UPLOADING" pixflow-web/src docs/design-docs/web.md docs/design-docs/frontend/upload.md

预期上传范围内无命中。文档为解释被删除旧行为而出现的明确否定性文字可以保留，但必须人工确认不是现行机制。

## Validation and Acceptance

标准 hash 验收：`sha256File` 对空文件得到 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`，对 `abc` 得到 `ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad`；跨多个读取块的随机输入与 Node 标准库结果一致。旧的分片摘要树实现应在这些跨块测试中失败。

协议验收：前端可以穷尽处理后端 record 的三种 init mode，RESUME 不产生本地 409。后端给出 `expectedChunks=4, uploadedChunks=[0,2]` 时，只调用 index 1 和 3 的 PUT；两者成功后 complete 恰好调用一次。PUT 返回 ALREADY_EXISTS 时计入成功。

错误验收：网络和 5xx 按现有 5-attempt/60-second 策略重试；429、CHUNK_* 4xx、UPLOAD_SESSION_NOT_FOUND 和 UPLOAD_SESSION_NOT_UPLOADING 不重试。complete 的 FILE_HASH_MISMATCH 可见且保留足够 traceId/message；它不触发 DELETE。

生命周期验收：pause 不调用 DELETE 且保留 localStorage；resume 必须再次 init 并消费 RESUME；cancel 立即改变 UI、清 localStorage并恰好 best-effort DELETE 一次；刷新不会发送 DELETE。paused/error job 在 UI 可见并有继续或重试入口。

真实续传验收：浏览器刷新前后 uploadId 相同，后端已确认的分片没有新的 PUT。再次选择内容不同但同名文件会得到不同 fileHash；再次选择相同内容在 active session 时 RESUME，在 READY 包存在时 DEDUP。

回归验收：上述 pnpm typecheck/test/build 和 module/file Maven test 全部通过。若 Docker 不可用，必须把真实 Redis/MinIO/浏览器刷新场景标为未验证，本计划不得仅凭 mock 测试宣告全部完成。

## Idempotence and Recovery

前端改动可通过测试反复执行。localStorage 只是提示；删除 `pixflow.upload.session.{fileHash}` 后重新选择文件仍会由后端按 fileHash 返回 RESUME。worker 对重复 PUT 依赖后端 ALREADY_EXISTS 幂等，因此测试或浏览器中断后可安全重试。

依赖安装只使用 pnpm，重复执行 `pnpm --dir pixflow-web add @noble/hashes` 不应创建重复条目。不要手工编辑 lockfile。若 worker 打包不兼容，先保持旧代码未删除并用定向构建验证替代实现；一旦新标准 hash 测试通过，再删除旧摘要树逻辑，不能保留运行时双协议开关，因为后端只接受标准 SHA-256。

真实测试产生的 active session 可通过 UI 取消或调用 DELETE 清理。完成或取消后后端会清 Redis 元数据和 `tmp-uploads/{uploadId}`；不要直接 FLUSHDB 或批量删除非本计划 key。Docker Compose 可反复启动；停止环境使用仓库日常方式，不加 `-v`，避免删除开发数据卷。

## Artifacts and Notes

实现完成后在此保留最小证据，格式示例：

    Hash vector: abc -> ba7816bf...0015ad
    Init: { mode: "RESUME", uploadId: "...", expectedChunks: 6, uploadedChunks: [0,1,2] }
    PUT observed after refresh: /chunks/3, /chunks/4, /chunks/5
    PUT not observed after refresh: /chunks/0, /chunks/1, /chunks/2
    Complete: { packageId: 123, status: "UPLOADED" }

本轮自动验证证据：

    Hash vector: abc -> ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
    Frontend: 24 test files, 114 tests passed; typecheck passed; Vite emitted fileHash.worker-*.js
    Module file: 32 tests passed; UploadSessionServiceTest 7, UploadSessionStoreContractTest 4
    Reactor: 30 modules install/compile passed with tests skipped
    Runtime blocker: TaskQueryController requires missing TaskCommandService bean
    Browser refresh/network validation: skipped by explicit user direction

不要保存真实预签名 URL、JWT、cookie 或完整 Redis value。只记录 uploadId 的短前缀、分片编号和脱敏 traceId。

## Interfaces and Dependencies

实施结束时，`pixflow-web/src/types/upload.ts` 必须有语义等价于以下联合类型，具体属性排序不重要：

    type InitUploadResponse =
      | { mode: 'UPLOAD'; uploadId: string; packageId: null; status: null; chunkSize: number; expectedChunks: number; uploadedChunks: number[] }
      | { mode: 'RESUME'; uploadId: string; packageId: null; status: 'UPLOADING'; chunkSize: number; expectedChunks: number; uploadedChunks: number[] }
      | { mode: 'DEDUP'; uploadId: null; packageId: number; status: 'READY'; chunkSize: 0; expectedChunks: 0; uploadedChunks: [] }

`sha256File` 必须接收 File、可选进度回调和 AbortSignal，返回标准整文件 SHA-256 hex。可以用 options 对象表达参数，但必须让取消从 uploadJob 传到 worker，且不能把 Vue 类型带入 hash 核心。

`runChunkPool` 必须显式接收 uploadId、File、server chunkSize、server expectedChunks、initialUploadedIndices、concurrency、AbortSignal 和回调。返回值必须能区分 success/failure/aborted，并暴露最终后端校准后的 uploaded set；不能自行调用 init、complete、DELETE 或 localStorage。

`UploadJobHandle` 必须提供 `run/pause/resume/cancel/retry`。uploadJob 是唯一编排 init、GET session、worker pool、complete 和 sessionStore 的位置；API adapter、Pinia store 和 Vue 组件不得复制协议状态机。

新增唯一生产依赖 `@noble/hashes` 用于增量整包摘要。Vue、Pinia、Vite、Web Crypto、fetch 和后端端点保持现有依赖与路径；不引入 Uppy、tus、Axios、IndexedDB 持久化或前端分布式锁。

## Revision Notes

2026-07-13 / Codex: 创建本计划。根据当前前后端设计与真实实现，固定标准整文件 SHA-256、独立 RESUME 模式、后端快照驱动差集上传、网络/5xx 有界重试、暂停与取消分离，以及跨端自动测试和真实刷新续传验收。

2026-07-13 / Codex: 完成 Milestone 1-4 与 Milestone 5 的自动验证部分。实现增量 worker hash、严格上传联合类型、后端快照差集 worker、generation 守卫及暂停/取消 UI；更新正确的可执行 jar 启动步骤。浏览器验收按用户要求跳过，应用 HTTP 验收被并行 execution-domain 的 `TaskCommandService` Bean 缺失阻断，因此 Milestone 5 保持未完成。

2026-07-13 / Codex: 根据双轴 code review 补齐 AbortSignal 从 uploadJob 到 init/GET/PUT/complete 的传递，并让 Pinia 同步完整状态快照；新增网络错误恢复、5 次 attempt 耗尽和在飞 PUT abort 回归测试。复核未发现剩余代码或 spec 一致性问题；仅保留已记录的真实运行验收缺口。

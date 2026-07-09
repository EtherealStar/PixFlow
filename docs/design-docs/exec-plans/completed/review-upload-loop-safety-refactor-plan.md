# 按代码审查报告重构上传、loop 运行态与 trace 安全边界

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文面向完全不了解当前会话的执行者：只要有当前工作树、这份计划和代码审查报告，就应能完成重构、验证结果，并知道为什么这样做。

## Purpose / Big Picture

这份计划要按代码审查报告彻底修复两个高风险面：`pixflow-module-file` 的分片上传与素材包生命周期，以及 `pixflow-loop` 的执行循环、权限上下文、流式恢复和 trace 记录。完成后，用户能安全上传大 zip，取消与完成不会并发打架，失败的合包/落库/投递不会留下互相矛盾的包状态；Agent loop 不再按每个回合私自创建无法关闭的线程池，格式错误的 tool-call JSON 不会被当成业务参数执行，模型输出截断能够触发恢复，trace 数据不会重复、被后续修改或产生负延迟。

本计划不是兼容性补丁。凡是审查报告指出的旧路径本身就是风险来源的，都要重构删除或替换：删除 `readAllBytes()` 分片路径，删除 complete/cancel 双锁终态路径，删除默认“永不引用”的生产删除检查，删除 loop 内部自建工具线程池，删除 malformed JSON 继续执行工具的行为，删除重复写 tool trace 的路径。不保留旧 bean 名、旧 status 字符串契约、旧宽松 metadata 契约或旧 snapshot 父版本作为兼容分支。

## Progress

- [x] (2026-07-09 09:45+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和当前 active exec plans，确认本计划必须放在 `docs/design-docs/exec-plans/` 且要遵守现有 agent/context 自动配置重构边界。
- [x] (2026-07-09 10:00+08:00) 阅读 `docs/design-docs/design.md`、`docs/design-docs/module/file.md`、`docs/design-docs/harness/loop.md`、`docs/design-docs/harness/tools.md`、`docs/design-docs/infra/permission.md`、`docs/design-docs/harness/context.md`、`docs/design-docs/harness/hooks.md`、`docs/design-docs/harness/eval.md`，确认上传、loop、permission、hooks、eval 的目标机制。
- [x] (2026-07-09 10:15+08:00) 阅读代码审查报告，归纳出上传完成一致性、包引用删除、loop 线程池、tool JSON 解析、stop reason、公共 record 校验、trace 语义、自动配置与 POM 版本九组问题。
- [x] (2026-07-09 10:35+08:00) 读取 `UploadSessionService`、`AssetPackageService`、`DefaultPackageReferenceChecker`、`FileAutoConfiguration`、`AgentLoop`、`ModelStreamConsumer`、`LoopProperties`、`RuntimeState`、`Attachment`、`AgentEvent`、`DefaultPermissionContextFactory`、`TraceFanout`、`LoopToolTraceSink`、`RuntimeScopeTranslator`、`LoopAutoConfiguration`、`AgentTurnRunner`、`CompleteUploadResponse`，确认审查报告中的热点仍在当前工作树。
- [x] (2026-07-09 10:55+08:00) 新建本中文 ExecPlan，明确修复机制、实施顺序、删除旧代码范围和验证命令。
- [x] (2026-07-09 01:10+08:00) 重构 `pixflow-module-file` 上传完成事务边界、流式分片写入、单终态锁、READY-only 去重和 fail-safe 删除语义。
- [x] (2026-07-09 01:25+08:00) 重构 `pixflow-loop` 工具线程池生命周期、tool-call JSON 解析失败路径、stop reason 传播、权限上下文解析、公共 record/metadata 校验和 trace 单一写入路径。
- [x] (2026-07-09 01:55+08:00) 更新自动配置、SPI/设计文档和测试；snapshot 父版本未改，因根项目仍是 `1.0.0-SNAPSHOT` 开发态，已在本计划记录冲突。
- [x] (2026-07-09 01:46+08:00) 运行 file、loop、app 聚合验证命令，并把实际输出写入 `Outcomes & Retrospective`。

## Surprises & Discoveries

- Observation: `UploadSessionService.findReadyPackage` 名字说 READY，但查询只按 `fileHash` 和 `deletedAt is null`，未限制 `PackageStatus.READY`。
  Evidence: 当前方法只调用 `.eq(AssetPackage::getFileHash, fileHash).isNull(AssetPackage::getDeletedAt).last("limit 1")`。这会把 `UPLOADED`、`EXTRACTING`、`FAILED`、`PARTIAL` 都当成 dedup 命中。

- Observation: 上传完成和取消使用两个不同锁，且 complete 在合包前创建 package 行；如果 compose/hash/mark/publish 任一阶段失败，会产生需要补偿的跨存储不一致。
  Evidence: `complete` 使用 `upload/lock/{uploadId}/complete`，`cancel` 使用 `upload/lock/{uploadId}/cancel`；`complete` 先 `packageService.createUploadingPackage(...)`，随后才 `writeComposedObject(...)`、`markSourceStored(...)`、`extractionPublisher.publish(...)`。

- Observation: 分片上传先 `body.readAllBytes()`，再检查声明大小和 hash；这会在限制生效前把攻击者输入放入堆内存。
  Evidence: `putChunk` 内先 `bytes = body.readAllBytes()`，之后才比较 `declaredSize != bytes.length` 和 hash。

- Observation: `DefaultPackageReferenceChecker` 的生产默认返回 `false`，这不是“未知时保守”，而是“未知时物理删除”。
  Evidence: `AssetPackageService.delete` 在 `isReferenced(packageId)` 为 false 时执行 `objectStorage.deleteByPrefix(...)` 和 `packageMapper.deleteById(...)`。

- Observation: `AgentLoop` 每个实例都创建 `Executors.newFixedThreadPool(...)`，但没有关闭路径；同时 `LoopProperties.toolConcurrencyPoolSize` 只检查大于等于 1，没有上限。
  Evidence: `AgentLoop` 构造函数内 `Executors.newFixedThreadPool(poolSize, ...)`，类不实现 `AutoCloseable`，没有 `shutdown`；`LoopProperties.setToolConcurrencyPoolSize` 未限制最大值。

- Observation: tool-call JSON 解析失败后会继续执行工具，参数是 `__parseError` 和 `raw`。
  Evidence: `AgentLoop.parseArguments` catch `JsonProcessingException` 后返回 `Map.of("__parseError", ..., "raw", argumentsJson)`，`toHarnessToolCall` 继续创建 `ToolCall`。

- Observation: `ModelStreamConsumer` 丢弃完成事件的 stop reason，并固定返回 `StopReason.STOP`、`outputInterrupted=false`，使 `OutputInterruptHandler` 永远无法被长度截断触发。
  Evidence: `consume` 最终 `new ModelOutcome(..., StopReason.STOP, usage, false, null)`。

- Observation: trace 责任当前有重复路径：tools 的 `LoopToolTraceSink` 记录一条，`AgentLoop` 还对每个 result 调 `traceFanout.fanoutToolResult(...)` 再记一条。
  Evidence: `executeToolCalls` 创建 `new LoopToolTraceSink(turnTrace)` 放入 `ToolExecutionContext`，随后对 `results` 循环调用 `traceFanout.fanoutToolResult(result, 0L)`。

- Observation: `TraceFanout.fanoutHookSpan` 把 hook 阻断记录为 `PERMISSION/TERMINATE`，与 `hooks.md` 对 PreToolUse 软阻断必须是 `VALIDATION/SKIP` 的设计冲突。
  Evidence: `TraceFanout` 在 `result.blocked()` 时构造 `new TraceError(..., "HOOK_BLOCKED", "PERMISSION", "TERMINATE", ...)`。

- Observation: `LoopAutoConfiguration` 总是注册 `PermissionContextFactory`，没有 `@ConditionalOnMissingBean`，会挡住应用提供的自定义工厂。
  Evidence: `LoopAutoConfiguration.permissionContextFactory()` 只有 `@Bean`，没有条件注解。

- Observation: 审查项要求子模块 parent version 不保留 snapshot，但当前根项目、`pixflow-loop` 与 `pixflow-module-file` 的 parent 均仍是 `1.0.0-SNAPSHOT`，仓库处于开发态且没有可引用的 release 父版本。
  Evidence: `rg -n "<version>.*-SNAPSHOT</version>" pixflow-loop/pom.xml pixflow-module-file/pom.xml pom.xml` 命中根 `pom.xml:14`、`pixflow-module-file/pom.xml:9`、`pixflow-loop/pom.xml:9`。本次不伪造 release 版本，只记录冲突等待后续发布决策。

## Decision Log

- Decision: 上传完成使用单一“终态锁”串行化 complete 和 cancel，不再保留 complete/cancel 双锁。
  Rationale: complete 和 cancel 都会删除临时分片、更新 session 终态和影响 active upload 索引，本质是同一会话终态竞争。两个不同锁会允许并发交错，造成对象已删除但合包仍读取、或合包成功后 session 被取消。
  Date/Author: 2026-07-09 / Codex

- Decision: 分片写入改为“限流流式写临时对象 + DigestInputStream 校验”，删除堆内 `readAllBytes()` 路径。
  Rationale: 后端必须在读取过程中执行字节上限，不能先把全部 body 缓进堆里再判断。`ObjectStorage.put(InputStream, declaredLength, ...)` 已支持流式输入，chunk hash 可通过 digest 包裹流计算。
  Date/Author: 2026-07-09 / Codex

- Decision: dedup 只承认 `PackageStatus.READY` 且未软删的包。
  Rationale: `UPLOADED` 只代表 source.zip 已落库等待解压，`EXTRACTING` 仍可能失败，`FAILED` 明确不可用，`PARTIAL` 对“整包已就绪”的语义也不等同。为避免客户端拿到不可用包，init 的 DEDUP 只返回 READY。
  Date/Author: 2026-07-09 / Codex

- Decision: 默认包引用检查改为 fail-safe：没有权威引用检查器时只允许软删，不允许物理删对象和 DB 行。
  Rationale: `file.md` 的删除语义是“物理删除受限、被引用则软删”。在 task/conversation 引用检查没有完整接入时，默认 false 会把未知当成无引用，风险比保守软删更高。
  Date/Author: 2026-07-09 / Codex

- Decision: loop 的工具执行 executor 改为外部托管 Bean，由 auto-configuration 发布，`AgentLoop` 构造注入。
  Rationale: `AgentLoop` 是 per-turn/per-session 现场对象，不应拥有无法关闭的线程池。线程池是进程级资源，应由 Spring 生命周期 `destroyMethod=shutdown` 管理，并用有界配置限制大小。
  Date/Author: 2026-07-09 / Codex

- Decision: tool-call JSON 解析失败必须在进入 tools 执行管线前变成结构化 tool error，不执行业务 handler。
  Rationale: malformed JSON 是模型输出格式错误，不是业务工具参数。把 `__parseError` 传给 handler 会让任何 handler 都被迫识别这个私有字段，且可能绕过 schema 失败的语义。
  Date/Author: 2026-07-09 / Codex

- Decision: trace 只保留一条 canonical tool span 路径：保留 `LoopToolTraceSink`，删除 `TraceFanout.fanoutToolResult` 的主循环调用和方法。
  Rationale: tools 执行管线已经拥有 classification、permission、rewritten、externalized、metadata 等更完整语义。loop 级二次记录只会造成重复或口径不一致。
  Date/Author: 2026-07-09 / Codex

- Decision: 公共 record 和 metadata API 统一收紧为“必填字段显式拒绝 null；可选 Map 归一化为空并要求 key 非空、value 为可序列化简单值”。
  Rationale: 这些类型是跨模块边界。让非法值靠 JDK 偶发 NPE 或 ClassCastException 暴露，会让调用方难以定位。统一构造校验可以把错误提前到入口。
  Date/Author: 2026-07-09 / Codex

- Decision: 不在本计划里改回 app 全包扫描或发布 per-turn `RuntimeState` Bean。
  Rationale: 已有 active exec plans 明确 context/agent 自动配置边界，`RuntimeState` 仍是每回合运行态。审查报告中的 auto-configuration 问题应通过条件 Bean 和托管 executor 修复，不回退生命周期边界。
  Date/Author: 2026-07-09 / Codex

## Outcomes & Retrospective

本计划已实施代码并通过本轮验证。

已完成的主要结果：

- `pixflow-module-file`：分片上传改为 `ChunkInputVerifier` 流式限长 + SHA-256 校验，失败清理临时对象；complete/cancel 共用 `upload/lock/{uploadId}/terminal`；complete 先进入 `COMPLETING`，校验分片覆盖、分片大小与总大小，再创建包和写 source；dedup 只返回 `PackageStatus.READY` 且 `deleted_at IS NULL` 的包；默认引用检查改为 `ConservativePackageReferenceChecker`，未知引用状态只软删。
- `pixflow-loop`：`AgentLoop` 使用注入的 `loopToolExecutor`，auto-configuration 托管线程池生命周期；`toolConcurrencyPoolSize` 限制为 1..64；malformed tool-call JSON 直接生成 `invalid_tool_input` 工具错误，不调用 `ToolExecutor`；`ModelStreamConsumer` 传播 completed stop reason，`StopReason.LENGTH` 置 `outputInterrupted=true`；工具 trace 只保留 `LoopToolTraceSink` 单一路径，metadata 防御性复制并修正异常 timestamp；hook 阻断 trace 记录为 `VALIDATION/SKIP`。
- 公共契约：`Attachment`、`AgentEvent`、`RuntimeState` metadata 入口收紧，非法 key/value 早失败；`DefaultPermissionContextFactory` 修正逗号字符串 trim/filter；`RuntimeScopeTranslator` 保留 `WORKER` round-trip。
- 文档：`docs/design-docs/module/file.md` 与 `docs/design-docs/harness/loop.md` 已同步本次实现口径。

验证结果：

    mvn -pl pixflow-module-file -am test
    Result: PASS

    mvn -pl pixflow-loop -am test
    Result: PASS

    mvn -pl pixflow-module-file,pixflow-loop -am test
    Result: PASS. First sandbox attempt failed before Maven started with Windows `CreateProcessAsUserW failed: 5`; rerun outside sandbox succeeded with reactor `BUILD SUCCESS` at 2026-07-09T01:46:40+08:00.

    mvn -pl pixflow-app -am -DskipTests compile
    Result: PASS

收尾风险检查：

    rg -n "fanoutToolResult|__parseError|newFixedThreadPool|DefaultPackageReferenceChecker|readAllBytes" pixflow-module-file/src/main/java pixflow-loop/src/main/java pixflow-agent/src/main/java

结果：`pixflow-module-file` 与 `pixflow-loop` 生产代码不再命中本计划旧风险路径；剩余命中位于 `pixflow-agent`，对应另一个 active exec plan 的范围。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。本计划涉及两个主要 Maven 模块。

`pixflow-module-file` 是素材入口模块，负责分片上传、整包 SHA-256 去重、source.zip 对象落库、发布解压消息、素材包查询和删除。关键文件是 `pixflow-module-file/src/main/java/com/pixflow/module/file/upload/UploadSessionService.java`、`UploadSessionStore.java`、`CompleteUploadResponse.java`、`pixflow-module-file/src/main/java/com/pixflow/module/file/pkg/AssetPackageService.java`、`DefaultPackageReferenceChecker.java`、`PackageReferenceChecker.java` 和 `pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileAutoConfiguration.java`。

`pixflow-loop` 是 Agent think-act-observe 主循环模块，负责每回合构建 context snapshot、调用模型、解析 tool calls、调用 tools 执行管线、派发 hooks、记录 eval trace 和处理 CONTEXT_LIMIT / 输出截断恢复。关键文件是 `pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java`、`ModelStreamConsumer.java`、`LoopProperties.java`、`LoopAutoConfiguration.java`、`RuntimeState.java`、`Attachment.java`、`AgentEvent.java`、`DefaultPermissionContextFactory.java`、`TraceFanout.java`、`LoopToolTraceSink.java`、`RuntimeScopeTranslator.java` 和 `AgentTurnRunner.java`。

这里的“终态锁”指同一个 upload session 的 complete 和 cancel 都必须竞争的同一把锁，例如 `pixflow:upload:lock:{uploadId}:terminal`。持有这把锁的线程可以把 session 从 `UPLOADING` 迁移到 `COMPLETING`、`READY` 或 `CANCELLED`；其他终态操作必须看到新状态后幂等返回或拒绝。

这里的“fail-safe 删除”指引用状态未知时不做物理删除。物理删除会删除 MinIO 对象前缀和 DB 行，不可轻易恢复；软删只写 `asset_package.deleted_at`，列表默认不可见，但历史任务和对话仍可通过 ID 读取必要元数据。

这里的“canonical trace path”指一个工具调用只由一个路径写入 `TurnTrace.recordToolCall`。本计划选择保留 tools 执行管线发出的 `ToolTraceSink` 事件，因为它最接近真实执行边界；loop 仍 emit `AgentEvent.toolResult` 给 SSE，但不再额外写一条 eval tool span。

## Plan of Work

第一阶段重构上传分片写入。修改 `UploadSessionService.putChunk`，删除 `body.readAllBytes()` 和 `sha256(byte[])`。新增一个小的包内 helper，例如 `BoundedDigestInputStream` 或 `ChunkInputVerifier`，它接收原始 `InputStream`、最大允许读取字节数、期望 hash 和期望长度。读取时最多允许 `expectedChunkLength + 1` 字节，超出立即抛 `CHUNK_SIZE_MISMATCH`，并用 `DigestInputStream` 计算 SHA-256。正常路径把流直接传给 `ObjectStorage.put(ObjectLocation.of(BucketType.TMP, key), verifiedStream, expectedLength, "application/octet-stream")`。如果当前 `ObjectStorage.put` 要求在调用前知道长度，则长度来自 `declaredSize` 和 session 计算出的期望分片长度；最后一个分片允许小于 `chunkSize`，非末片必须等于 `chunkSize`。写入成功后只保存 `ChunkMetadata(index, hash, verifiedBytes, key)`。

第二阶段重构 upload session 终态状态机。`complete` 和 `cancel` 都改用同一把锁，例如 `namespace.key("upload", "lock", uploadId, "terminal")`。`complete` 进入锁后重新读取 session，只有 `UPLOADING` 可继续；先把 session 状态更新为 `COMPLETING`，防止新的 chunk PUT 被接受。`cancel` 进入同一锁后重新读取 session：如果已经 `READY` 则返回已完成信息或拒绝取消；如果是 `UPLOADING` 或 `COMPLETING`，按当前已知 chunk 元数据清理临时对象并把 session 删除或标记 `CANCELLED`。删除旧的 complete/cancel 两锁路径，不留兼容分支。

第三阶段重构 complete 的一致性与补偿。`complete` 在终态锁内先 `loadAllChunks`，再验证三个不变量：分片索引覆盖 `[0, expectedChunks)`；分片大小之和严格等于 `session.size()`；非末片大小等于 `session.chunkSize()` 且末片在合法范围内。然后创建 package 行、写 source.zip、校验全量 hash、标记 source stored、发布解压消息。每个跨存储阶段都要有明确补偿：

    compose 或 hash 校验失败：删除已创建的 package 行，删除可能写出的 source object，保留 session 为 UPLOADING 或 FAILED_COMPLETING 供用户重试。
    markSourceStored 失败：删除 source object 和 package 行，保留 session/临时分片供重试。
    publish 失败：不删除 source object 和 package 行；包保持 UPLOADED，让 PublishGapRescan 按设计补发。complete 返回 packageId 和 PackageStatus.UPLOADED，或者返回明确“已上传待解压投递重试”的状态。
    complete 成功进入 READY session 后，才清理临时 chunk 对象和 active upload key。

第四阶段收敛 dedup 与 response 类型。把 `findReadyPackage` 改名为 `findDedupReadyPackage`，查询必须同时满足 `file_hash = ?`、`deleted_at IS NULL`、`status = PackageStatus.READY`。`CompleteUploadResponse` 的 `status` 字段从 `String` 改为 `PackageStatus`。所有返回 `"UPLOADED"` 的地方改成 `PackageStatus.UPLOADED`。同步更新 controller 和测试，删除任何把生命周期状态作为裸字符串比较的断言。

第五阶段重构素材包删除引用检查。删除 `DefaultPackageReferenceChecker` 的“永不引用”实现，替换为保守默认，例如 `ConservativePackageReferenceChecker`，没有权威引用检查器时返回 `true`，从而只软删。更完整的做法是定义 `PackageReferenceProbe` 列表：每个探针能检查 task、conversation 或其他表是否引用 package；`CompositePackageReferenceChecker` 只有在至少一个权威探针存在且所有探针都确认未引用时才允许物理删除。当前若 task/conversation 还没有实现探针，默认路径就是软删。`FileAutoConfiguration` 不应再注册“默认无引用”的 bean。

第六阶段重构 loop 工具 executor 生命周期。新增 `LoopExecutorAutoConfiguration` 或扩展 `LoopAutoConfiguration`，发布一个命名清楚的 Bean，例如 `loopToolExecutor`，类型为 `ExecutorService`，`@Bean(name = LOOP_TOOL_EXECUTOR_BEAN, destroyMethod = "shutdown")`，并加 `@ConditionalOnMissingBean(name = LOOP_TOOL_EXECUTOR_BEAN)`。`AgentLoop` 构造函数新增 `ExecutorService toolExecutorService` 参数，不再调用 `Executors.newFixedThreadPool`。调用方 `AgentOrchestrator` 或 loop 构造处必须传入该 Bean。测试 helper 用 direct executor 或小型 executor 替代。删除 `AgentLoop` 内部自建线程池代码。

第七阶段给 loop 并发配置加上限。`LoopProperties` 增加 `maxToolConcurrencyPoolSize` 常量或直接把 `toolConcurrencyPoolSize` 限制在安全上限，例如 64。setter 中 `value < 1 || value > 64` 直接抛 `IllegalArgumentException`，错误码和文档同步说明。新增测试覆盖 0、1、64、65。不要在运行时静默 clamp，因为错误配置应在启动期暴露。

第八阶段重构 tool-call JSON 解析失败路径。新增一个内部结果类型，例如 `ParsedToolCall`，它要么持有 `ToolCall`，要么持有 parse error。`AgentLoop` 处理 infra tool calls 时，解析失败的调用不进入 `toolExecutor.execute`；直接生成一个 `ToolExecutionResult(error=true)`，`toolName` 使用原始工具名，content 是安全的 `invalid_tool_input` 文案，metadata 包含 `errorCategory=VALIDATION`、`recovery=SKIP`、脱敏后的解析错误和 `rawLength`，但不把完整 raw JSON 传给模型或 handler。这样 malformed JSON 会作为工具错误回填模型，主循环继续，符合 tools 文档的 schema/输入错误语义。

第九阶段恢复 stop reason 传播。修改 `ModelStreamConsumer`：在收到 `ChatStreamEvent.Completed` 时保存 `completed.stopReason()` 或当前 `infra-ai` 暴露的等价字段；如果 stop reason 是 `StopReason.LENGTH` 或 completed 显式 `outputInterrupted=true`，则返回 `ModelOutcome.outputInterrupted=true`。如果当前 `ChatStreamEvent.Completed` 没有字段，先在 `infra-ai` 的 record 中补齐 provider-neutral stop reason，并更新对应 adapter 测试。`AgentLoop` 不改变续轮判定，仍只看 tool calls；stop reason 只用于输出截断恢复。

第十阶段收紧 public record 与 metadata。`Attachment` compact constructor 应 `Objects.requireNonNull` 或显式拒绝空白 `id`、`kind`、`reference`，metadata 允许 null 但要归一化为空 Map，并校验 key 非空且 value 是 String/Number/Boolean/Enum/Map/List/null 等 JSON 可序列化简单值。`AgentEvent` 必须拒绝 null `type`，metadata 同样归一化和校验。`RuntimeState.putMetadata` 拒绝 null key，value 要么为可序列化简单值，要么为 null 表示删除；`metadataOrDefault` 增加类型安全重载，或至少在类型不匹配时返回 default 并记录明确异常，不再让调用点遭遇 `ClassCastException`。删除未使用的 `RuntimeState.copyMetadata`。

第十一阶段修复权限上下文解析和自动配置。`DefaultPermissionContextFactory.toStringSet` 对字符串按逗号拆分后必须 `trim()`，过滤空字符串，保持插入顺序。`PermissionContextFactory` Javadoc 明确 null state、空 conversationId、非法 metadata 的行为：默认实现对空 state 或空 conversationId fail-fast；非法 set 元素按 trim 后过滤或转字符串；非法 subagent map 返回 null 或抛错必须写清。`LoopAutoConfiguration.permissionContextFactory()` 加 `@ConditionalOnMissingBean(PermissionContextFactory.class)`，允许应用提供自定义工厂。

第十二阶段修复 trace 语义。保留 `LoopToolTraceSink` 作为唯一 tool trace 写入路径，删除 `TraceFanout.fanoutToolResult` 方法和 `AgentLoop` 中对它的调用，避免重复工具记录。`LoopToolTraceSink` 和 `TraceFanout.fanoutToolTraceEvent` 需要对 metadata 做防御性复制，处理 null metadata，并对 timestamp 做约束：如果 started/finished 小于等于 0，则用 `Instant.now()`；如果 finished < started，则 latency 记 0 且 metadata 标注 `timestampCorrected=true`。`TraceFanout.fanoutHookSpan` 的 hook blocked 错误从 `PERMISSION/TERMINATE` 改为 `VALIDATION/SKIP`，与 hooks 文档一致。

第十三阶段修复 runtime scope round-trip。`RuntimeScopeTranslator` 当前 `toEval` 把 hooks scope 的所有 subagent 都转为 `SUB_AGENT`，`WORKER` 只有 eval→hooks 时能表达。改造方式是给 hooks `RuntimeScope` 或 state metadata 增加稳定 scope kind，至少能区分 main、subagent、worker；`toEval` 对 worker 返回 `RuntimeScope.WORKER`，`toHooks(RuntimeScope.WORKER)` 仍返回 worker。新增 round-trip 测试，证明 MAIN、SUB_AGENT、WORKER 不丢失。

第十四阶段修正文档和 SPI 说明。更新 `AgentTurnRunner` Javadoc，让它只描述实际签名 `stream(String conversationId, String prompt, List<Attachment> attachments, AgentEventSink sink)`，不要再说前 4 参和第 5/6 参由 caller 控制。更新 `docs/design-docs/module/file.md` 的 Revision Notes，记录 dedup 只认 READY、默认删除 fail-safe、complete/cancel 单终态锁。更新 `docs/design-docs/harness/loop.md` Revision Notes，记录托管 tool executor、malformed JSON 工具错误、stop reason 恢复和 trace 单一路径。

第十五阶段处理 POM 版本卫生。如果父项目当前已经有非 snapshot release 版本，`pixflow-loop/pom.xml` 和 `pixflow-module-file/pom.xml` 的 parent version 改为该 release 版本。如果整个仓库仍处于 `1.0.0-SNAPSHOT` 开发态且没有可用 release 父版本，则不要伪造版本；在本计划 `Surprises & Discoveries` 记录“审查规则与仓库当前开发态冲突”，并等待用户确认是否要发布父 POM。无论如何，新增或修改的版本声明不得引入新的 `-SNAPSHOT`。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。

先确认当前风险点仍存在：

    rg -n "readAllBytes|__parseError|fanoutToolResult|newFixedThreadPool|DefaultPackageReferenceChecker|CompleteUploadResponse\\(long packageId, String status\\)" pixflow-module-file/src/main/java pixflow-loop/src/main/java

预期当前能看到上传、loop、trace 和 response 的旧路径。实施完成后同一命令不应再命中生产旧路径。允许命中新计划文档本身，不允许命中生产 Java 代码。

上传模块 focused 测试建议新增或更新：

    pixflow-module-file/src/test/java/com/pixflow/module/file/upload/UploadSessionServiceTest.java
    pixflow-module-file/src/test/java/com/pixflow/module/file/pkg/AssetPackageServiceTest.java
    pixflow-module-file/src/test/java/com/pixflow/module/file/config/FileAutoConfigurationTest.java

测试场景至少覆盖：chunk 超声明大小时不会完整读入堆、chunk hash mismatch 不写临时对象、complete 校验 chunk size sum、complete/cancel 同锁串行、compose/hash/mark 失败补偿、publish 失败保留 UPLOADED 供 rescan、dedup 只返回 READY、无引用探针时删除只软删。

运行：

    mvn -pl pixflow-module-file -am test

loop focused 测试建议新增或更新：

    pixflow-loop/src/test/java/com/pixflow/harness/loop/AgentLoopContinuationDecisionTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/AgentLoopErrorRecoveryTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/DefaultPermissionContextFactoryTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/LoopToolTraceSinkTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/TraceFanoutTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/RuntimeStateTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/RuntimeScopeTranslatorTest.java
    pixflow-loop/src/test/java/com/pixflow/harness/loop/config/LoopAutoConfigurationTest.java

测试场景至少覆盖：`LoopProperties` 拒绝过大 pool size；`AgentLoop` 使用注入 executor；malformed tool JSON 产生 tool error 且不调用 handler；`ModelStreamConsumer` 对 LENGTH 返回 `outputInterrupted=true`；`DefaultPermissionContextFactory` trim 逗号字符串；`AgentEvent` 拒绝 null type；`Attachment` 拒绝 null required 字段；trace 不重复记录工具调用；hook 阻断 trace 是 `VALIDATION/SKIP`；WORKER scope round-trip 不丢失。

运行：

    mvn -pl pixflow-loop -am test

跨模块验证：

    mvn -pl pixflow-module-file,pixflow-loop -am test
    mvn -pl pixflow-app -am -DskipTests compile

如果修改了 `infra-ai` 的 `ChatStreamEvent.Completed` stop reason 字段，还要运行：

    mvn -pl pixflow-infra-ai,pixflow-loop -am test

收尾检查：

    rg -n "readAllBytes|__parseError|raw\"|fanoutToolResult|newFixedThreadPool|DefaultPackageReferenceChecker|status\\)" pixflow-module-file/src/main/java pixflow-loop/src/main/java
    rg -n "RuntimeState runtimeState|private final RuntimeState runtimeState" pixflow-agent/src/main/java pixflow-loop/src/main/java
    rg -n "<version>.*-SNAPSHOT</version>" pixflow-loop/pom.xml pixflow-module-file/pom.xml

第一条不应命中旧风险路径；第二条不应在 Spring 单例组件里命中 per-turn state 注入；第三条要按第十五阶段的版本决策处理并记录。

## Validation and Acceptance

上传验收：一个恶意客户端发送大于配置 chunk size 的 body 时，后端在读取超过允许字节后立即失败，不把完整 body 缓进堆；`ObjectStorage.put` 不被调用，返回 `CHUNK_SIZE_MISMATCH`。完整上传时，所有 chunk 的 `chunkSize` 总和必须等于 session size，合包后全量 SHA-256 必须等于 `CompleteUploadRequest.fileHash` 或 session fileHash。任一 compose/hash/mark 失败都不会留下可 dedup 的 package；publish 失败只留下 `UPLOADED` 包并由 `PublishGapRescan` 补发，不留下 session/active upload 的矛盾状态。

删除验收：没有任何业务模块提供权威引用探针时，`AssetPackageService.delete(packageId)` 只写 `deleted_at`，不调用 `deleteByPrefix` 和 `deleteById`。当测试提供一个明确“无引用”的探针集合时，才允许物理删除。已引用包始终软删。

loop 资源验收：创建和销毁多个 `AgentLoop` 不会增加无法回收的 `loop-tool-*` 线程；Spring context 关闭时 `loopToolExecutor` 被 `shutdown`。配置 `pixflow.loop.tool-concurrency-pool-size=100000` 在绑定或 setter 阶段失败，而不是创建巨大线程池。

tool JSON 验收：模型返回 malformed arguments JSON 时，业务 handler 不被调用，message store 得到一条 tool result，内容是结构化 `invalid_tool_input` 错误；主循环可以继续下一轮。

输出截断验收：`ModelStreamConsumer` 消费 stop reason 为 LENGTH 的 completed event 后，`ModelOutcome.outputInterrupted()` 为 true；`AgentLoop` 触发 `MAX_OUTPUT_TOKENS_ESCALATE`，下一次截断触发 `MAX_OUTPUT_TOKENS_RECOVERY`，与 `OutputInterruptHandler` 现有设计一致。

trace 验收：一次工具调用最多写一条 `TraceToolCall`；metadata 在 trace 写入后不会因为原始 result metadata 后续修改而变化；timestamp 为 0 或倒序时不会产生 epoch 时间或负 latency；hook 阻断记录为 `VALIDATION/SKIP`。

公共契约验收：`new Attachment(null, ...)`、`new AgentEvent(null, ...)`、`state.putMetadata(null, value)` 都明确失败；metadata 非序列化对象被拒绝或转换前明确失败；`metadataOrDefault` 不再让调用方在类型不匹配时遭遇不明 `ClassCastException`。

自动配置验收：用户提供自定义 `PermissionContextFactory` Bean 时，`LoopAutoConfiguration` 不创建默认工厂；默认路径仍能创建 `DefaultPermissionContextFactory`。

## Idempotence and Recovery

本计划的修改可分阶段执行。每个阶段完成后运行对应模块测试，失败时不要回滚到旧风险路径，而要沿新边界修复调用点或测试替身。

上传重构中，临时分片对象删除和 package/source 补偿必须幂等。补偿删除对象时，如果对象不存在，应视为成功；删除 package 行时，如果行已经不存在，应视为成功。complete 失败后保留的 session 必须允许用户重试或取消，不能卡在无法处理的中间态。

loop executor 重构中，如果某些测试直接 new `AgentLoop` 编译失败，应更新测试 helper 统一注入 executor；不要在 `AgentLoop` 内恢复 `Executors.newFixedThreadPool`。如果确实需要局部快速测试，传入 `Executors.newSingleThreadExecutor` 并在测试 `@AfterEach` 关闭。

trace 重构中，如果删除 `fanoutToolResult` 后某些断言少了一条 tool span，应改断言为单一路径的字段完整性，而不是重新添加第二条 span。重复 trace 是本计划要删除的旧行为。

POM 版本如果无法按审查规则改为 release，应暂停该小项并记录冲突，不要随意发布或伪造不存在的父版本。

## Artifacts and Notes

审查报告对应的最高风险证据：

    UploadSessionService.putChunk:
        bytes = body.readAllBytes();

    UploadSessionService.complete:
        lock key = upload/lock/{uploadId}/complete
        createUploadingPackage before compose/hash/mark/publish

    UploadSessionService.cancel:
        lock key = upload/lock/{uploadId}/cancel

    DefaultPackageReferenceChecker:
        return false;

    AgentLoop constructor:
        Executors.newFixedThreadPool(poolSize, ...)

    AgentLoop.parseArguments:
        return Map.of("__parseError", ..., "raw", argumentsJson);

    ModelStreamConsumer:
        StopReason.STOP, outputInterrupted=false

    AgentLoop.executeToolCalls:
        new LoopToolTraceSink(turnTrace)
        traceFanout.fanoutToolResult(result, 0L)

目标机制摘要：

    上传：streaming bounded digest -> tmp object -> chunk metadata -> terminal lock -> verified compose -> source object/package -> publish or rescan -> cleanup chunks
    删除：unknown references => soft delete; physical delete only after authoritative unreferenced decision
    loop：managed executor bean -> parse failures as tool errors -> stop reason propagated -> single trace sink -> strict public boundary validation

## Interfaces and Dependencies

本计划完成后，`CompleteUploadResponse` 应使用 typed status：

    package com.pixflow.module.file.upload;

    import com.pixflow.module.file.pkg.PackageStatus;

    public record CompleteUploadResponse(long packageId, PackageStatus status) {}

`UploadSessionService` 应不再包含：

    body.readAllBytes()
    sha256(byte[])
    namespace.key("upload", "lock", uploadId, "complete")
    namespace.key("upload", "lock", uploadId, "cancel")

应存在一个单一终态锁命名约定：

    namespace.key("upload", "lock", uploadId, "terminal")

`LoopAutoConfiguration` 或新增 loop executor 自动配置应提供：

    public static final String LOOP_TOOL_EXECUTOR_BEAN = "loopToolExecutor";

    @Bean(name = LOOP_TOOL_EXECUTOR_BEAN, destroyMethod = "shutdown")
    @ConditionalOnMissingBean(name = LOOP_TOOL_EXECUTOR_BEAN)
    ExecutorService loopToolExecutor(LoopProperties properties)

`AgentLoop` 构造函数必须接收这个 executor，不能自己创建：

    public AgentLoop(..., LoopProperties properties, ObjectMapper jsonMapper, ExecutorService toolExecutorService)

如果为了保持已有测试构造器，可以提供重载，但重载也必须要求传入 executor 或使用测试专用工厂；生产构造路径不得内部 new 线程池。

`PermissionContextFactory` 默认 Bean 必须允许覆盖：

    @Bean
    @ConditionalOnMissingBean(PermissionContextFactory.class)
    public PermissionContextFactory permissionContextFactory()

`TraceFanout` 不应再有 `fanoutToolResult(ToolExecutionResult, long)`；工具执行 trace 由 `LoopToolTraceSink.record(ToolTraceEvent)` 单独负责。

## Revision Notes

2026-07-09 / Codex: 新建本 ExecPlan。原因是代码审查报告指出 `pixflow-module-file` 和 `pixflow-loop` 存在上传一致性、删除安全、线程资源、权限解析、输出截断和 trace 数据语义等高风险问题。用户要求中文计划、说明修复机制与思路，并要求重构性完全修复、不保留旧代码。因此本计划选择删除旧风险路径，按设计文档收敛为流式上传、单终态锁、READY-only dedup、fail-safe 删除、托管 executor、parse error tool result、stop reason 恢复和单一 trace 写入路径。

2026-07-09 / Codex: 实施并验证本计划。更新原因是代码已完成流式分片校验、单终态锁、READY-only dedup、保守删除、托管 loop executor、malformed JSON 工具错误、stop reason 传播、metadata 契约收紧和 trace 单一路径；同时同步 `module/file.md`、`harness/loop.md` 并记录 Maven 验证结果。POM snapshot 版本因根项目仍为开发态未改，作为审查规则与当前仓库状态冲突记录。

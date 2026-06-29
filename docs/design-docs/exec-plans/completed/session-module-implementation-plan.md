# 实现 harness/session 会话 transcript 持久化

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文只规划 `harness/session` 模块和为它前置抽取的共享 `ToolResultStorage`，不实现 `harness/loop`、`module/conversation`、`agent` 或前端；这些模块只通过本文描述的接口在后续接线。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有生产级会话 transcript 事实源。用户和 Agent 的消息、工具结果、附件消息、压缩边界和摘要都会由 `harness/session` 作为唯一写者写入 MySQL `message` 表；系统在任意节点、任意回合开始时都能从 MySQL 重建“压缩后的活动链”，继续长对话而不把已摘要历史重新塞回模型窗口。

这个计划的可观察效果不是新增一个普通消息表，而是以下行为都能被测试证明：重复 append 不写重复消息；大 tool result 被外置到 MinIO 并在 rehydrate 时回读；多次 destructive compaction 后 `load()` 返回 `[最新 boundary, 最新 summary, 普通 tail...]`，旧 marker 和旧普通消息不会污染模型上下文；`module/conversation` 以后只能只读展示全量 transcript，不能绕过 session 写 `message`。

## Progress

- [x] (2026-06-29 23:40+08:00) 已阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/harness/session.md`、`docs/design-docs/harness/context.md`、`docs/design-docs/infra/storage.md`、`docs/design-docs/design.md` 和 `docs/design-docs/exec-plans/module-dependency-dag-plan.md`，确认 session 属于 Wave 3，且当前不是 MVP 阶段。
- [x] (2026-06-29 23:50+08:00) 已同步旧设计文档：`design.md §13.1` 扩展 `message` 并新增 `message_compaction`；DAG 补 `storage --> session`；`storage.md`、`context.md`、`session.md` 明确现在就把共享 `ToolResultStorage` 抽到 `infra/storage`。
- [x] (2026-06-29 23:55+08:00) 已确认用户决策：数据库 schema 使用 migration 风格；共享 tool-result 外置抽象现在就抽取，而不是留到后续。
- [x] (2026-06-29 15:43+08:00) 已新增 `pixflow-session` Maven 模块，并接入根 `pom.xml` 的 `<modules>` 与 `dependencyManagement`。
- [x] (2026-06-29 15:43+08:00) 已在 `pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/toolresult/` 抽取共享 `ToolResultStorage`、`StoredToolResultReference`、`StoredToolResultContent` 与 `ObjectStorageToolResultStorage`，并在 `StorageAutoConfiguration` 中装配共享 bean。
- [x] (2026-06-29 15:43+08:00) 已用 migration 风格新增 `pixflow-session/src/main/resources/db/migration/V1__create_session_transcript.sql`，创建 `message` 与 `message_compaction` 表。
- [x] (2026-06-29 15:48+08:00) 已实现 `TranscriptPort` 基础全链路：`append` 外置大 tool result、分配 seq、幂等写入；`load` 按最新 compaction 纪元重建活动链并回读外置结果；`replaceForCompaction` 先 flush，再写 boundary/summary marker 与 `message_compaction`。
- [x] (2026-06-29 15:48+08:00) 已补基础单元测试和架构测试：`ObjectStorageToolResultStorageTest`、`TranscriptServiceTest`、`SessionArchitectureTest`。`mvn -pl pixflow-infra-storage -am test` 与 `mvn -pl pixflow-session -am test` 均为 `BUILD SUCCESS`；Docker 不可用，现有 Testcontainers 集成测试按条件跳过。
- [ ] 补更细粒度的 session 单元测试：metadata 映射、重复 append 幂等、seq 唯一冲突重试、多次 compaction、marker 缺失降级、BUFFERED flush 前后可见性、外置对象缺失降级。
- [ ] 补 MySQL/Testcontainers migration/schema 与 transcript 端到端集成测试；本机 Docker 当前不可用，尚未执行真实 MySQL + MinIO 集成验证。

## Surprises & Discoveries

- Observation: 设计文档此前提到共享 `ToolResultStorage`，但当前源码中 `pixflow-infra-storage` 只有 `ObjectStorage` 和 `StorageKeys`，context 自己有 `ObjectStorageToolResultExternalizer` 薄适配。
  Evidence: `pixflow-context/src/main/java/com/pixflow/harness/context/budget/ObjectStorageToolResultExternalizer.java` 直接调用 `StorageKeys.toolResult(id)`；`pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/` 当前没有 `toolresult` 包。

- Observation: 当前仓库已出现两种 schema 管理风格，部分模块用 `schema.sql`，`pixflow-module-dag` 已有 `src/main/resources/db/migration/V1__create_pending_plan.sql`。
  Evidence: `rg --files | rg "schema.sql|db/migration"` 能看到 `pixflow-module-memory/src/main/resources/schema.sql` 和 `pixflow-module-dag/src/main/resources/db/migration/V1__create_pending_plan.sql`。本计划按用户要求采用 migration 风格。

- Observation: `context.Message`、`MessageMetadata`、`TranscriptPort` 已经是实际源码中的稳定接缝，session 不能重新定义自己的消息模型。
  Evidence: `pixflow-context/src/main/java/com/pixflow/harness/context/store/TranscriptPort.java` 已定义 `append/load/replaceForCompaction`；`Message` 是 record，包含 `id/role/content/toolCallId/metadata/createdAt`。

- Observation: `MessageStore.flush()` 目前只是预留生命周期入口，还不会向下游 `TranscriptPort` 传播 flush。
  Evidence: `pixflow-context/src/main/java/com/pixflow/harness/context/store/MessageStore.java` 的 `flush()` 方法当前没有调用 transcript port。session 本次实现提供了 `TranscriptService.flush(conversationId)` 和 `flushAll()`，后续 loop/context 接线时需要补齐显式 flush 传播。

- Observation: 现有 Testcontainers 环境在本机无法找到有效 Docker 环境，因此只能完成纯单元/架构测试，未完成真实 MySQL/MinIO 集成验证。
  Evidence: `mvn -pl pixflow-session -am test` 输出中 `MinioObjectStorageIntegrationTest` 和 `RedisIntegrationTest` 均因 Docker 环境不可用按条件 skipped；session 当前尚未新增 MySQL Testcontainers 用例。

## Decision Log

- Decision: `harness/session` 新增为独立 Maven 模块 `pixflow-session`，包名使用 `com.pixflow.harness.session`。
  Rationale: session 是 Wave 3 横切模块，既实现 context 的 SPI，又拥有 MySQL transcript schema 和写路径守护。独立模块能清楚表达依赖方向：session 依赖 context、storage、common，但 context/conversation/loop 不反向依赖 session 实体。
  Date/Author: 2026-06-29 / Codex

- Decision: 数据库 schema 使用 migration 风格，新建 `pixflow-session/src/main/resources/db/migration/V1__create_session_transcript.sql`。
  Rationale: 用户明确选择 migration 风格。session 的 `message` 表是长期事实源，后续字段和索引演进需要可追踪版本；migration 比散落的 `schema.sql` 更适合生产级变更。
  Date/Author: 2026-06-29 / User + Codex

- Decision: 现在就把共享大 tool-result 外置抽象抽到 `pixflow-infra-storage`。
  Rationale: context、tools、session 都需要相同的 key/hash/preview/ref/missing 语义。如果 session 先复制 context 的薄适配，后续会出现三套 metadata 和对象 key 规则。把 `ToolResultStorage` 放在 storage 中，底层仍用 `ObjectStorage`，既复用 MinIO 原语又避免业务模块直接拼 key。
  Date/Author: 2026-06-29 / User + Codex

- Decision: session 的 `load()` 返回压缩后的活动链，而不是全量 transcript。
  Rationale: 全量 transcript 给 `module/conversation` 展示和审计使用；模型上下文需要的是经过最近 compaction 纪元派生出的活动链。把这个逻辑放在 session，能让写入格式和读取解释收敛在同一模块。
  Date/Author: 2026-06-29 / Codex

- Decision: session 不依赖 Redis，也不实现会话级并发锁。
  Rationale: 活动链缓存归 `context.MessageChainCache`，同会话回合串行归 loop/conversation 的上层锁。session 只用 MySQL 主键和 `(conversation_id, seq)` 唯一约束兜底，保持无状态、多节点可 rehydrate。
  Date/Author: 2026-06-29 / Codex

## Outcomes & Retrospective

当前计划已从文档同步推进到第一版 Java 实现。已落地的关键结果是：新增 `pixflow-session` 模块、MySQL migration、持久化实体与 mapper、`TranscriptService`、`ActiveChainResolver`、`TranscriptBuffer`、`SequenceAllocator`、`SessionToolResultExternalizer`、配置与自动装配；同时在 `pixflow-infra-storage` 中抽出共享 `ToolResultStorage`，让 session 不再复制 context 的对象 key/hash/preview 逻辑。

已通过的验证是：`mvn -pl pixflow-infra-storage -am test` 和 `mvn -pl pixflow-session -am test` 均 `BUILD SUCCESS`；新增 storage 纯单测证明同 toolCallId + 同 content 得到稳定 key、对象缺失返回 missing 语义；session 基础单测覆盖 append/load/replaceForCompaction 的内存替身链路；ArchUnit 覆盖 session 主代码不依赖 `module/*`、`harness/tools`、`infra/cache`、`harness/eval`，并守护 `MessageWriteMapper` 不被 session 包外引用。

仍需收尾的是集成级正确性和验收覆盖：尚未跑真实 MySQL migration/schema 测试，尚未补 Testcontainers MySQL + MinIO 的 append/load/compaction/外置端到端测试；多次 compaction、重复 append 幂等、seq 唯一冲突重试、marker 缺失降级、BUFFERED flush 可见性等验收点目前只由实现语义支撑，测试覆盖还不完整。由于本机 Docker 不可用，当前不能把未执行的集成测试描述为通过。

## Context and Orientation

PixFlow 是多模块 Maven 工程，仓库根目录是 `D:\study\PixFlow`。当前根 `pom.xml` 已包含 `pixflow-context`、`pixflow-infra-storage`、`pixflow-tools`、`pixflow-module-dag` 等模块，但还没有 `pixflow-session` 模块。session 的设计文档在 `docs/design-docs/harness/session.md`。context 的实际 Java 接缝在 `pixflow-context/src/main/java/com/pixflow/harness/context/store/TranscriptPort.java`，这个接口有三个方法：`append(String conversationId, List<Message> messages)`、`load(String conversationId)`、`replaceForCompaction(String conversationId, List<Message> messages, CompactionTrigger trigger, Map<String,Object> metadata)`。

这里定义几个必须理解的词。transcript 是同一会话里按时间产生的消息记录。append-only 是指数据库行只追加，不为了压缩而删除或改写旧消息。活动链是模型下一轮应该看到的消息链，它可能不是全量历史，因为旧历史会被 boundary 和 summary 折叠。compaction 纪元是 `message_compaction` 表里的一条记录，描述最近一次 destructive compaction 用哪两条 marker 消息代表已折叠前缀，以及普通消息 tail 从哪个 seq 之后开始。marker 是 `message.compaction_marker` 标出的特殊行，只能是 `BOUNDARY` 或 `SUMMARY`，永远不能进入普通 tail。

本计划涉及四组文件。第一组是 `pixflow-infra-storage`，要新增共享 `ToolResultStorage`。第二组是新模块 `pixflow-session`，要实现 `TranscriptPort`。第三组是根 `pom.xml`，要加入新模块和依赖管理。第四组是测试和 architecture guard，确保唯一写者和依赖边界真实生效。

实现前可用这些关键词快速定位参考设计文本：

- 在 `docs/design-docs/harness/session.md` 中搜索：`唯一写者`、`message_compaction`、`covered_up_to_seq`、`marker 永不进 tail`、`replaceForCompaction`、`turn 内缓冲`、`会话内 seq 分配`、`唯一写者约束守护`。
- 在 `docs/design-docs/harness/context.md` 中搜索：`TranscriptPort`、`MessageStore`、`MessageMetadata`、`destructive compaction`、`大结果外置`、`MessageChainCache`。
- 在 `docs/design-docs/infra/storage.md` 中搜索：`ToolResultStorage`、`StorageKeys.toolResult`、`ObjectStorage`、`TOOL_RESULTS`、`大 tool-result 外置`。
- 在 `docs/design-docs/design.md` 中搜索：`Context Manager`、`message_compaction`、`message`、`MinIO（对象）`、`message 表唯一写者`。
- 在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索：`storage --> session`、`harness/session`、`Wave 3`。

## Plan of Work

第一阶段是共享外置抽象。先在 `pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/toolresult/` 新增 `ToolResultStorage`、`ToolResultRecord` 或等价 record、`ObjectStorageToolResultStorage`。这个抽象不决定 50KB 阈值，只提供“给我 toolCallId、content、previewChars，我返回稳定引用”和“给我引用，我读回完整 content 或返回 missing”的能力。实现用 `StorageKeys.toolResult(id)` 生成位置，用 SHA-256 内容 hash 参与 id，使用 UTF-8 字节写入 `ObjectStorage.put`。同时保留 `pixflow-context` 现有 `ToolResultExternalizer` 接口不立刻破坏，但后续可让它委托新的 storage 抽象。

第二阶段是模块骨架。新增 `pixflow-session/pom.xml`，依赖 `pixflow-common`、`pixflow-context`、`pixflow-infra-storage`、`spring-context`、`spring-boot-autoconfigure`、MyBatis-Plus、测试依赖和 ArchUnit。根 `pom.xml` 的 `<modules>` 和 `<dependencyManagement>` 要加入 `pixflow-session`。新增 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 指向 `com.pixflow.harness.session.config.SessionAutoConfiguration`。

第三阶段是 migration。新增 `pixflow-session/src/main/resources/db/migration/V1__create_session_transcript.sql`。它创建 `message` 和 `message_compaction`。`message.id` 是 varchar 主键；`conversation_id` 非空；`seq` bigint 非空；`role` varchar 非空；`content` mediumtext；`tool_call_id` 可空；`compaction_marker` 可空；`metadata` JSON 可空；`attached_package_id`、`task_id` 可空；`created_at` timestamp 非空。约束至少包括主键 `id`、唯一键 `(conversation_id, seq)`、索引 `(conversation_id, compaction_marker)`。`message_compaction` 使用 bigint 自增主键，并对 `(conversation_id, id)` 建索引。migration 必须可重复由测试容器启动执行，不把 schema 写进应用代码。

第四阶段是持久化模型和 mapper。创建 `persistence/MessageEntity.java`、`CompactionEntity.java`、`MessageWriteMapper.java`、`MessageReadMapper.java`、`CompactionMapper.java`。如果使用 MyBatis-Plus，实体用项目现有风格；写 mapper 保持 session 包私有语义，不被其他模块导出。`MessageWriteMapper` 至少提供批量 insert、按 id 幂等插入或忽略重复、查询会话 max seq 的能力。`MessageReadMapper` 提供按 conversation 读普通消息、按 marker id 读 marker、按 tail 条件读普通消息的能力。

第五阶段是映射和 metadata。创建 `mapping/MessageMapper.java`，负责 `context.model.Message` 与 `MessageEntity` 双向转换。metadata 用 Jackson 或项目已有 JSON 工具序列化成 MySQL JSON 字符串，反序列化回 `MessageMetadata`。`compaction_marker` 不只依赖 metadata 推断，而是明确提列：`isCompactBoundary` 对应 `BOUNDARY`，`isCompactSummary` 对应 `SUMMARY`，普通消息为 null。

第六阶段是 seq、append 和 buffer。创建 `seq/SequenceAllocator.java`，按会话查询 `max(seq)+1` 后给同批消息连续分配。创建 `buffer/TranscriptBuffer.java`，支持 `BUFFERED` 和 `SYNC` 两种模式。默认 buffered 下 `append` 只入回合内缓冲，达到条数/字节阈值或回合边界 `flush()` 时批量落库；sync 下每次 append 立即落库。用户消息、assistant、tool_result、attachment 都走同一 append 路径。

第七阶段是 active chain resolver。创建 `chain/ActiveChainResolver.java`。算法必须按 `session.md` 执行：没有纪元时返回所有普通消息按 seq；有纪元时先按 `boundary_message_id` 和 `summary_message_id` 取 marker 行，再取 `compaction_marker IS NULL AND seq > covered_up_to_seq` 的普通 tail，最后拼成 `[boundary, summary] + tail`。如果 marker 行缺失，记录错误指标并退化为无纪元全量读取，但测试要覆盖这个降级。

第八阶段是 `TranscriptService`。创建 `persistence/TranscriptService.java` 或 `port/SessionTranscriptPort.java` 实现 `TranscriptPort`。`append` 流程是：外置大 tool_result，分配 seq，映射实体，幂等批量写入，返回回填后的 `Message` 列表。`load` 流程是：调用 resolver 得到活动链实体，映射为 `Message`，对 externalized tool result 回读完整 content，缺失则保留 preview 并写 metadata 标记。`replaceForCompaction` 流程是：先 flush；校验入参至少含 boundary 和 summary；外置其中可能存在的大 tool_result；插入 boundary/summary marker；根据 tail 中普通消息的最小 seq 计算 `covered_up_to_seq`，tail 为空则用当前普通消息 max seq；同一事务写 `message_compaction`。

第九阶段是配置、错误和指标。创建 `config/SessionProperties.java` 和 `SessionAutoConfiguration.java`，绑定 `pixflow.session.write-mode`、buffer 阈值、load max messages、externalize threshold、seq retry。创建 `error/SessionErrorCode.java`，至少包含 `SESSION_TRANSCRIPT_CORRUPTED` 和 `SESSION_SEQ_ALLOCATION_EXHAUSTED`。DB/storage 异常跨边界后按 common 归一化，session 不吞写失败。用 Micrometer 暴露 append、flush、load、compaction、externalize、seq retry 指标。

第十阶段是测试和架构守护。单元测试覆盖 mapper 以外的纯逻辑：metadata 映射、seq 分配、active chain resolver、多次 compaction、buffer flush、外置缺失降级。集成测试用 Testcontainers MySQL 和 MinIO 覆盖 append/load/replaceForCompaction 端到端；如果本地 Docker 不可用，测试应按环境条件跳过，并在结果中说明跳过原因。ArchUnit 测试必须断言 session 不依赖 `com.pixflow.module..`、`com.pixflow.harness.tools..`、`com.pixflow.infra.cache..`、`com.pixflow.harness.eval..`；还要断言 `MessageWriteMapper` 只被 `com.pixflow.harness.session..` 调用。

## Concrete Steps

从仓库根目录开始：

    cd D:\study\PixFlow
    git status --short

工作区可能已经有用户或其他执行者的文档改动。不要回滚无关文件，只在本计划列出的模块和文档范围内工作。

先确认参考设计和实际源码接缝：

    cd D:\study\PixFlow
    rg -n "TranscriptPort|record Message|MessageMetadata|CompactionTrigger" pixflow-context\src\main\java
    rg -n "ObjectStorage|StorageKeys|toolResult" pixflow-infra-storage\src\main\java docs\design-docs\infra\storage.md
    rg -n "marker 永不进 tail|covered_up_to_seq|唯一写者|turn 内缓冲" docs\design-docs\harness\session.md

新增共享 storage 抽象后运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-infra-storage -am test

期望结果是 Maven `BUILD SUCCESS`，并且新增的 `ToolResultStorage` 纯单元测试通过。若 MinIO 集成测试因未设置环境变量而跳过，输出应明确显示跳过，而不是误称通过。

新增 session 模块后运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-session -am test

若需要用 Windows Docker 命名管道运行 Testcontainers：

    cd D:\study\PixFlow
    mvn -pl pixflow-session -am -Pwindows-docker-npipe test

如果 Docker Desktop 只暴露 TCP 2375，则改用：

    cd D:\study\PixFlow
    mvn -pl pixflow-session -am -Pwindows-docker-tcp test

完成后检查依赖边界：

    cd D:\study\PixFlow
    rg -n "com\.pixflow\.module\.|com\.pixflow\.infra\.cache|com\.pixflow\.harness\.tools|com\.pixflow\.harness\.eval" pixflow-session\src\main\java

这个命令在 session 主代码中不应找到任何匹配。测试代码可以引用 ArchUnit 和测试替身，但不能把被禁止依赖带入主代码。

## Validation and Acceptance

第一类验收是共享外置抽象。构造同一个 `toolCallId` 和同一段 content，两次调用 `ToolResultStorage` 应返回同一稳定 key；同一个 `toolCallId` 但不同 content 应产生不同 key；回读应得到原始完整 content；对象缺失时应返回 missing 语义而不是让 session rehydrate 失败。运行 `mvn -pl pixflow-infra-storage -am test` 应通过新增测试。

第二类验收是 append 和幂等。对同一个 conversation append 三条消息，`load` 在无 compaction 纪元时按 seq 返回三条普通消息。重复 append 同一批带相同 id 的消息，不应产生重复行，`message` 表中同 conversation 的行数不变。测试应证明 `(conversation_id, seq)` 唯一冲突会触发有限重试，超过重试次数抛 `SESSION_SEQ_ALLOCATION_EXHAUSTED` 或等价 `PixFlowException`。

第三类验收是大 tool result。append 一个超过阈值的 `TOOL_RESULT` 后，`message.content` 中只保存引用和 preview，metadata 中存在 `toolResultExternalized=true` 和 `toolResultRef`。随后 `load` 应回读完整 content，让返回的 `Message.content()` 等于原始大内容。删除或模拟缺失对象后，`load` 不应失败，应返回 preview 并在 metadata 中标记 `missingExternalToolResult`。

第四类验收是压缩纪元。构造普通消息 seq 1 到 100，执行一次 compaction 保留 81 到 100，`load` 应返回 `[boundary1, summary1, 81..100]`。再追加 103 到 150，再执行第二次 compaction 保留 140 到 150，`load` 必须返回 `[boundary2, summary2, 140..150]`。旧 marker 不得出现在 tail 中，旧普通消息不得出现在活动链中。

第五类验收是 buffer。`BUFFERED` 模式下调用 `append` 后、flush 前，数据库中不应看到新行；flush 后行可见。`SYNC` 模式下 append 后立即可见。`replaceForCompaction` 前必须强制 flush，使 tail 的 id 和 seq 都能查到。

第六类验收是架构守护。运行 `mvn -pl pixflow-session -am test`，ArchUnit 测试应证明 session 主代码不依赖 `module/*`、`harness/tools`、`infra/cache`、`harness/eval`，并且 `MessageWriteMapper` 写方法只被 session 包调用。

## Idempotence and Recovery

本计划推荐 additive changes。新增 `pixflow-session` 模块、storage 的 `toolresult` 包、migration 文件和测试都可以重复运行。重复执行 Maven 测试不应改变工作区，除正常 target 输出外不产生持久副作用。

如果 migration 测试失败，先检查 SQL 是否能在 MySQL 8 语法下执行，尤其是 `JSON`、`MEDIUMTEXT`、索引名和 `TIMESTAMP` 默认值。不要用 H2 行为替代 MySQL 真实行为来证明 session schema 正确。

如果 `ToolResultStorage` 抽取导致 context 测试失败，优先用适配器保持 context 现有接口不变：`ContextBudgetService` 仍依赖 `ToolResultExternalizer`，但生产装配可以注入一个委托给 `infra/storage.toolresult.ToolResultStorage` 的实现。不要在同一提交里大规模重写 context budget 逻辑。

如果 Testcontainers 因 Docker 环境不可用跳过，必须在 `Progress` 或 `Outcomes & Retrospective` 记录“纯单元测试已通过，集成测试因 Docker 环境未执行或被条件跳过”。不要把未执行的集成测试描述为通过。

如果发现工作区有无关改动，继续只改本计划文件涉及的模块，不要执行 `git reset --hard` 或 `git checkout --` 回滚别人的工作。

## Artifacts and Notes

目标 migration 的结构应接近下面的形态，具体类型和索引名可按项目规范调整：

    CREATE TABLE IF NOT EXISTS message (
        id VARCHAR(64) PRIMARY KEY,
        conversation_id VARCHAR(64) NOT NULL,
        seq BIGINT NOT NULL,
        role VARCHAR(32) NOT NULL,
        content MEDIUMTEXT,
        tool_call_id VARCHAR(128),
        compaction_marker VARCHAR(32),
        metadata JSON,
        attached_package_id VARCHAR(64),
        task_id VARCHAR(64),
        created_at TIMESTAMP NOT NULL,
        CONSTRAINT uk_message_conversation_seq UNIQUE (conversation_id, seq),
        INDEX idx_message_conversation_marker (conversation_id, compaction_marker)
    );

    CREATE TABLE IF NOT EXISTS message_compaction (
        id BIGINT PRIMARY KEY AUTO_INCREMENT,
        conversation_id VARCHAR(64) NOT NULL,
        boundary_message_id VARCHAR(64) NOT NULL,
        summary_message_id VARCHAR(64) NOT NULL,
        covered_up_to_seq BIGINT NOT NULL,
        trigger VARCHAR(32) NOT NULL,
        created_at TIMESTAMP NOT NULL,
        INDEX idx_message_compaction_conversation_id (conversation_id, id)
    );

活动链重建的核心伪代码如下，测试必须围绕它写：

    latest = compactionMapper.findLatest(conversationId)
    if latest is null:
        return messageReadMapper.findNormalMessages(conversationId)
    boundary = messageReadMapper.findById(latest.boundaryMessageId)
    summary = messageReadMapper.findById(latest.summaryMessageId)
    tail = messageReadMapper.findNormalMessagesAfter(conversationId, latest.coveredUpToSeq)
    return [boundary, summary] + tail

这里 normal message 的意思是 `compaction_marker IS NULL`。这是防止旧 boundary/summary marker 污染 tail 的关键条件。

## Interfaces and Dependencies

在 `pixflow-infra-storage/src/main/java/com/pixflow/infra/storage/toolresult/` 中定义共享抽象。推荐接口形状如下，实际命名可微调，但能力不能缩水：

    public interface ToolResultStorage {
        ToolResultReference write(String toolCallId, String content, int previewChars);
        ToolResultContent read(ToolResultReference reference);
    }

    public record ToolResultReference(String id, String bucket, String key, String preview, long originalBytes, boolean missing) {}

    public record ToolResultContent(String content, ToolResultReference reference) {}

如果为了避免与 `pixflow-context` 现有 `com.pixflow.harness.context.model.ToolResultReference` 重名，可以在 storage 中使用 `StoredToolResultRef`，再由 context/session 映射为 context 模型。关键是 storage 侧统一 key/hash/preview/missing 语义。

在 `pixflow-session/pom.xml` 中至少依赖：

    com.pixflow:pixflow-common
    com.pixflow:pixflow-context
    com.pixflow:pixflow-infra-storage
    com.baomidou:mybatis-plus-boot-starter
    org.springframework.boot:spring-boot-autoconfigure
    org.springframework:spring-context
    org.springframework.boot:spring-boot-starter-test (test scope)
    com.tngtech.archunit:archunit-junit5 (test scope)
    org.testcontainers:mysql (test scope)

在 `pixflow-session/src/main/java/com/pixflow/harness/session/` 中最终应存在这些包和类型：

    config/SessionProperties.java
    config/SessionAutoConfiguration.java
    persistence/TranscriptService.java
    persistence/MessageEntity.java
    persistence/CompactionEntity.java
    persistence/MessageWriteMapper.java
    persistence/MessageReadMapper.java
    persistence/CompactionMapper.java
    chain/ActiveChainResolver.java
    buffer/TranscriptBuffer.java
    externalize/SessionToolResultExternalizer.java
    seq/SequenceAllocator.java
    mapping/MessageMapper.java
    error/SessionErrorCode.java

`TranscriptService` 必须实现 `com.pixflow.harness.context.store.TranscriptPort`。所有对外返回和接收的消息类型必须是 `com.pixflow.harness.context.model.Message`，不要把 `MessageEntity` 暴露给 context、conversation 或 loop。

在 `pixflow-session/src/test/java/com/pixflow/harness/session/architecture/SessionArchitectureTest.java` 中使用 ArchUnit。规则至少包括：

    noClasses().that().resideInAPackage("com.pixflow.harness.session..")
        .should().dependOnClassesThat().resideInAnyPackage(
            "com.pixflow.module..",
            "com.pixflow.harness.tools..",
            "com.pixflow.infra.cache..",
            "com.pixflow.harness.eval..");

并增加一条针对 `MessageWriteMapper` 调用者的规则，确保写 mapper 不被 session 包外引用。

## Change Notes

2026-06-29 / Codex: 创建 `harness/session` 中文 ExecPlan。原因是用户确认按完整项目实现 session 模块，不走 MVP；同时明确 schema 使用 migration 风格，并要求现在就抽取共享 `ToolResultStorage`。本文已把架构机制、设计检索关键词、实施步骤和验收行为写成可由后续执行者独立完成的计划。

2026-06-29 / Codex: 更新执行进度。原因是已完成第一版 `pixflow-session` 代码实现与共享 `infra/storage.toolresult` 抽取，并通过 `mvn -pl pixflow-infra-storage -am test`、`mvn -pl pixflow-session -am test`；同时记录 Docker/Testcontainers 集成验证尚未执行，以及后续需补齐的细粒度测试和 loop/context flush 接线。

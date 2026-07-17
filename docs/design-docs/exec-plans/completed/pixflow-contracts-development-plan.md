# 将 pixflow-contracts 收敛为 canonical Asset Reference 纯契约模块

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`，并细化 `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md` 的 Milestone 0 和 Milestone 1。执行者只依赖当前工作树和本文，就应能把 `pixflow-contracts` 从错误承载 Proposal 的共享 DTO 模块，重构为只承载 canonical Asset Reference（规范素材引用）纯形状与纯解析机制的零运行时依赖模块。每个停止点都必须更新本文；改变接口、模块所有权、实施顺序、兼容策略或验证命令时，还必须同步更新四个 living sections 与文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，Web、Conversation、Agent、File、Vision、DAG、Task 和 Imagegen 之间传递素材时，都可以依赖同一个由后端产生的 `referenceKey`。`package:123`、`package:123/sku:SKU-001` 和 `package:123/image:789` 会被同一套纯 Java 机制解析、规范化序列化和拒绝非法输入；显示名称、对象存储 key、旧的独立 `packageId` / `imageIds` / `source_image_ids` 不再充当跨模块身份。

同时，`pixflow-contracts` 不再保存 `ProposalDraft` 或 `ProposalPublicationPort`。Proposal 是 Conversation 的业务对象：DAG 和 Imagegen 只产出各自已经深校验的不可变 payload，`pixflow-app` 的工具适配器把这些 payload 交给 Conversation 的公开发布命令。这样既避免 DAG、Imagegen、Conversation 之间的 Maven 循环，也避免把 Proposal 状态、幂等、过期和持久化语义塞进“中立共享 DTO”。

可观察结果有三类。第一，contracts 单测证明所有合法 key 可 round-trip，非法 kind、非正 ID、非法 UTF-8、非规范百分号编码和多余路径段都被拒绝。第二，生产源码负向搜索不再命中 `com.pixflow.contracts.proposal`，DAG/Imagegen 也不再手工拼接或拆分 key。第三，真实工具路径仍能发布一个 ephemeral Proposal，同一 `toolCallId` 重放得到同一 `proposalId`，但 Proposal 的类型和状态只存在于 Conversation。

## Progress

- [x] (2026-07-17) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md` 和 `docs/design-docs/exec-plans/` 下全部活动计划。
- [x] (2026-07-17) 阅读 contracts、Asset Reference、File、Conversation、DAG、Imagegen 目标设计，Context Map、相关 CONTEXT 文档和 ADR 0005、0006、0007。
- [x] (2026-07-17) 审计 `pixflow-contracts` 当前源码、POM、Proposal 生产/发布链、File 私有 reference parser、直接 Maven 消费方和当前工作树。
- [x] (2026-07-17) 创建本中文 ExecPlan，固定 Asset Reference 编码机制、Proposal 所有权迁移、先 lint 后测试的门禁顺序和验收方式。
- [x] (2026-07-17) Milestone 0：冻结工作树与 TDD seam；记录 codec 缺失红灯，并在不覆盖 Conversation/File 既有修改的前提下完成切换。
- [x] (2026-07-17) Milestone 1：实现 dependency-free sealed Asset Reference 值层级与严格 canonical codec；38 项 codec 合同和 1 项架构守护通过。
- [x] (2026-07-17) Milestone 2：File permission proof、DAG、Imagegen、Task 与 App 统一使用 typed reference；删除私有 parser、手工 serializer 和 Imagegen 多源输入。
- [x] (2026-07-17) Milestone 3：Proposal 发布迁到 App/Conversation 所有权边界；删除 contracts Proposal 包、producer tool handler、旧 repository 与无效 Maven 依赖。
- [x] (2026-07-17) Milestone 4：补齐 contracts 与 producer ownership 架构守护、负向搜索和双轴代码审查；7 个触达模块共 359 项测试全绿。全仓 lint 的首个范围外阻断已按测试名/文件/规则记录，未越界修改 Loop/DAG 基线。

## Surprises & Discoveries

- Observation: 当前 `pixflow-contracts` 已经删除历史 confirmation 包，但仍只包含两个 Proposal 类型，正好与最新 `base/contracts.md` 的所有权规则相反。
  Evidence: `pixflow-contracts/src/main/java/com/pixflow/contracts/proposal/ProposalDraft.java` 和 `ProposalPublicationPort.java` 是当前仅有生产源码；目标设计明确“Proposal belongs to Conversation”。

- Observation: 当前 Proposal 共享类型不是孤立文件；DAG、Imagegen 和 Conversation 都直接依赖它，直接删除会让三个模块无法编译。
  Evidence: `DagProposalService`、`ImagegenPlanService` 构造 `ProposalDraft`，`PendingProposalRepository` 实现 `ProposalPublicationPort`。

- Observation: 当前代码已有多个互相漂移的 reference 语法实现。
  Evidence: `AssetPermissionProof` 私有 `Reference.parse` 手工切分 key；`DagProposalService.packageId` 手工截取 package；`ImagegenPlanService` 用字符串连接构造 IMAGE key。

- Observation: 数据库中的 package id 和 image id 是正数 `long`，但多个旧模块 API 为迁移方便使用 `String`。
  Evidence: File 的 `AssetImage.packageId` 和上传响应使用 `Long/long`；旧 `SourceImageInfo`、`ImagegenPlan` 和 `PackageReference` 使用字符串。

- Observation: contracts 的 Enforcer 已禁止内部模块、Spring、Jackson 和 Lombok，但当前没有行为测试，因而无法证明 parser 的规范性或值对象不变量。
  Evidence: `pixflow-contracts/pom.xml` 只有 `maven-enforcer-plugin`，模块下没有 `src/test`。

- Observation: 活动 Lint 计划要求 Maven reactor 串行运行，而且用户明确要求测试前先 lint。
  Evidence: `lint-baseline-remediation-plan.md` 记录并行 reactor 会争用 `target/spotbugsTemp.xml`；本计划所有 `test` 命令前都安排相同或更大范围的 `-DskipTests verify`。

- Observation: 当前工作树已有 Conversation 测试和 File permission 测试的用户修改。
  Evidence: 计划创建时 `git status --short` 显示两个 Conversation 测试为修改状态，`pixflow-module-file/src/test/java/com/pixflow/module/file/permission/` 为未跟踪目录。实施者必须在这些修改之上做最小补丁，不能覆盖或删除。

- Observation: `$implement` 技能要求优先采用 TDD；本文已经把 codec、File proof、producer validated API、Conversation Proposal service 和 App adapters 明确为公共测试 seam。
  Evidence: 第一轮以 `CanonicalAssetReferenceCodecContractTest` 从缺失类型开始，且测试前仍严格执行 contracts 范围的 `-DskipTests verify`。

- Observation: 第一轮缺失合同能够准确捕获尚未实现的 API，且没有被 lint/编译阶段误报为测试结果。
  Evidence: `mvn -pl pixflow-contracts -am -DskipTests verify` 于 2026-07-17 19:44+08:00 成功；随后 `mvn -pl pixflow-contracts -am test` 仅因 `ClassNotFoundException: com.pixflow.contracts.asset.CanonicalAssetReferenceCodec` 失败，1 test run、1 failure、0 skipped。

- Observation: “异常消息不包含输入”的直接 substring 断言不适用于空串和单空格样本，因为任意字符串包含空串，固定英文消息也自然含空格。
  Evidence: 首次 codec 行为测试 38 项中仅参数索引 2、3 的脱敏断言失败；改为断言消息完全由稳定 `reason` 映射后，既覆盖脱敏意图也不依赖输入字符巧合。

- Observation: File 私有 parser 会把 leading-zero package id 当作有效数字，并继续读取 owner facts；mock 依赖随后抛错，最终错误地映射成 `UNAVAILABLE`。
  Evidence: `AssetPermissionProofTest.nonCanonicalReferenceIsDeniedBeforeReadingOwnerFacts` 在旧实现下期望 `DENIED`、实际得到 `UNAVAILABLE`；这证明重复 grammar 已发生语义漂移。

- Observation: Maven incremental compiler 曾让 Imagegen 测试源码缺失 `LinkedHashSet` import 延迟到 Surefire 运行时才暴露为 unresolved compilation problem。
  Evidence: 同范围 lint 首次成功后，Imagegen 前 48 项测试通过、validator 5 项失败；恢复显式 import 并重新 lint 后 53 项 Imagegen 测试全部通过。

- Observation: 带 `-am` 或全仓 lint 会先被本计划范围外的 `pixflow-loop/RuntimeState.java` 既有 Checkstyle 基线阻断。
  Evidence: `RuntimeState.java` 当前有 4 项 `EmptyLineSeparator` 和 1 项 `MethodName`；本计划不修改该范围外文件，触达的 Contracts/File/DAG/Imagegen/Task/Conversation/App 模块不带 `-am` lint 均通过。

- Observation: 清理并重编译 DAG 后，SpotBugs 还暴露了范围外 `StepSpecCompiler.longValue(Object)` 的 `DM_BOXED_PRIMITIVE_FOR_PARSING` 既有问题；该文件相对 `HEAD` 无差异。
  Evidence: 8 模块最终 lint 在 DAG SpotBugs 报告 `StepSpecCompiler.java:50`，而全仓 lint 更早在 Loop Checkstyle 停止；本计划没有借 contracts 重构修改这两个无关实现。

- Observation: 双轴代码审查发现多源配置/错误码、Proposal CAS API 泄漏和 Imagegen plan 平行身份仍是 breaking refactor 遗留。
  Evidence: 已删除 `maxSourceImages`、`IMAGEGEN_TOO_MANY_SOURCES` 和字符串 hasher 重载；`ProposalService` 现在只公开 `ProposalSnapshot` 与 proposalId command seam，`PendingProposal`/status/CAS/future 均为包内实现；`ImagegenPlan` 构造时强制 IMAGE key 与 packageId 一致。

## Decision Log

- Decision: `pixflow-contracts` 只保留纯 JDK、不可变、跨边界且确实阻止重复解析的 Asset Reference 形状和 codec；不接收 Proposal、Activity、Permission proof、File resolver、Spring Bean 或业务 service。
  Rationale: 共享模块不是 DTO 桶。reference grammar 是所有素材消费者共同遵守的基础合同，而资源存在性、owner、readiness、显示快照和 expansion 都需要 File 当前事实，必须留在 Asset Library。
  Date/Author: 2026-07-17 / Codex

- Decision: 使用 sealed interface 加三个 record 表达 PACKAGE、SKU、IMAGE，而不是一个允许 nullable `skuId/imageId` 组合的 record。
  Rationale: 类型系统可以让 `PACKAGE + imageId`、`IMAGE + skuId` 等非法组合不可构造；调用方按 subtype 分支也比重复检查 kind 与 nullable 字段更安全。
  Date/Author: 2026-07-17 / Codex

- Decision: packageId 和 imageId 在 contracts 中使用正数 `long`；skuId 保留解码后的原始非空字符串，不 trim、不改大小写。
  Rationale: 两个数字来自稳定数据库 ID，使用 long 可在边界拒绝非数字、零、负数和溢出。SKU 是业务身份，擅自 trim 或 case-fold 会把不同资源合并。
  Date/Author: 2026-07-17 / Codex

- Decision: SKU path segment 使用 UTF-8 RFC 3986 风格百分号编码：ASCII unreserved 字符 `A-Z a-z 0-9 - . _ ~` 原样保留，其余 UTF-8 byte 使用大写 `%HH`；不使用 `URLEncoder` 的 form-urlencoded `+` 语义。
  Rationale: referenceKey 是路径式 canonical identity，不是表单字段。parse 后重新 serialize 必须与原输入逐字一致，因此小写 `%hh`、对 unreserved 字符的多余转义、`+`、非法 UTF-8 和不完整转义都能被确定性拒绝。
  Date/Author: 2026-07-17 / Codex

- Decision: Proposal 的生产方只返回 producer-owned validated payload；App 工具适配器负责调用 Conversation-owned `ProposalService.publish`。
  Rationale: App 已经依赖 DAG、Imagegen、Conversation 和 Tools，是无循环的组合根。把编排放在 App 后，DAG/Imagegen 不需要依赖 Conversation，也不需要一个中立 Proposal SPI。
  Date/Author: 2026-07-17 / Codex

- Decision: 迁移采用一次性切换，不保留旧 Proposal package、deprecated wrapper、字符串 parser fallback、旧 `packageId/imageIds/source_image_ids` 并行输入或双发布路径。
  Rationale: 仓库仍处于开发期；兼容层会制造两个身份真相源或两个 Proposal 所有者，并让架构守护无法证明旧路径已经消失。
  Date/Author: 2026-07-17 / Codex

- Decision: 每一轮测试前必须先对同一范围或更大范围运行 `mvn ... -DskipTests verify`，确认 Checkstyle 与 SpotBugs 通过；不把 `verify` 误记为测试成功。
  Rationale: 这是用户明确要求，也与活动 lint 计划的串行门禁一致。测试失败后修代码，再次测试前仍要重新 lint。
  Date/Author: 2026-07-17 / Codex

## Outcomes & Retrospective

核心重构已经落地：contracts 现在只公开纯 JDK Asset Reference 值对象、严格 codec 和稳定脱敏异常；Conversation 公开 proposalId command + `ProposalSnapshot`，内部 entity/status/CAS/future 不穿出 owner API；DAG/Imagegen 只返回 owner-owned validated payload，App 是唯一 Proposal tool 编排位置。旧 contracts Proposal、producer tool handler、Conversation repository、多源 Imagegen 输入/配置/错误码、File 私有 parser 及无真实 import 的 Maven 依赖均已删除。

最终触达 reactor 共 359 项测试全绿且零跳过：Contracts 39、Imagegen 56、File 34、DAG 139、Task 30、Conversation 41、App 20。Contracts、Imagegen、Task、Conversation、Agent 与 App 的本次代码均有成功 Checkstyle/SpotBugs 证据；DAG 的 Checkstyle 与 139 项测试通过，但清理重编译后被未改动 `StepSpecCompiler.java:50` 的既有 SpotBugs 告警阻断。全仓 `mvn -DskipTests verify` 在第 14/30 个模块 `pixflow-loop` 因未改动 `RuntimeState.java` 的 5 项 Checkstyle 基线停止，因此按“lint 先于测试”的约束没有把 `mvn verify` 虚报或继续当作全仓成功。

此结果只完成 canonical grammar、File permission parser 与 Proposal ownership 切片；File 的完整 resolve/inspect/expand、Generated Image publication 和 Asset Library 生命周期仍属于 `pixflow-file-development-plan.md`，不得视为已经完成。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。`pixflow-contracts` 是 Maven reactor 最低层模块之一，包根为 `com.pixflow.contracts`。它的 POM 在 validate 阶段禁止任何 `com.pixflow:*`、Spring、Jackson 和 Lombok 依赖。它可以包含 interface、sealed interface、record、enum 和只依赖 JDK 的确定性算法，但不能包含 Spring 自动配置、JSON 注解、数据库 mapper、Redis/MinIO client、业务异常或 owner 查询。

Asset Reference 是跨模块素材身份。PACKAGE key 标识一个 Asset Package；SKU key 标识该包内的 SKU scope；IMAGE key 标识一个 Original Image 或 Generated Image。key 只由后端 codec 生成，客户端和 Agent 只能原样保存、展示和回传。解析成功只证明语法和类型组合有效，不证明资源存在、属于当前管理员、未删除或可处理；这些当前事实由 `pixflow-module-file` 的 resolver 与 permission proof 负责。

Proposal 是 Conversation 拥有的、已经完整校验并等待用户一次确认或拒绝的请求。Pending Proposal 只在当前进程/浏览器 runtime 中短暂存在。DAG 拥有 Canonical DAG 与 validated deterministic payload，Imagegen 拥有 Validated Redraw Request，Conversation 拥有 `proposalId`、pending status、confirm/reject、并发 CAS 和以 `proposalId` 创建 Task 的幂等语义。`pixflow-app` 只编排这些公开 API，不重新实现任何一方的校验。

当前关键文件如下：

- `pixflow-contracts/pom.xml`：零运行时依赖与 Enforcer 守护，目标是保留并补 test-scope JUnit。
- `pixflow-contracts/src/main/java/com/pixflow/contracts/proposal/`：当前错误归属的 Proposal 共享包，目标是消费者迁移后删除。
- `pixflow-module-file/src/main/java/com/pixflow/module/file/permission/AssetPermissionProof.java`：当前重复实现 reference grammar，目标是使用 contracts parser 后只保留 File 当前事实证明。
- `pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/DagProposalService.java`：当前同时深校验和发布 Proposal，目标是只返回 DAG-owned validated payload。
- `pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/proposal/ImagegenPlanService.java`：当前手工拼 IMAGE key 并发布共享 Proposal，目标是只返回 Imagegen-owned validated redraw payload。
- `pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingProposalRepository.java`：当前已经拥有 ephemeral store、toolCallId 幂等与 confirm CAS，目标是改为实现 Conversation-owned service，而不是 contracts SPI。
- `pixflow-app/src/main/java/com/pixflow/app/`：组合根，目标是增加 deterministic/redraw Proposal 工具适配与装配测试。

开始实施前必须重新阅读当时所有活动 ExecPlan。本文直接依赖的权威参考文档和阅读目的如下：

- `PLANS.md`：ExecPlan 的 living sections、可执行性、验收与修订规则。
- `docs/design-docs/exec-plans/backend-architecture-alignment-refactor-plan.md`：本计划的阶段位置、禁止放入 contracts 的类型和 Asset Reference 总验收。
- `docs/design-docs/index.md`：确认当前权威文档位置，避免引用已废弃的根级 API 文档。
- `docs/design-docs/base/contracts.md`：共享形状准入规则、Proposal 所有权和 legacy 合同删除清单。
- `docs/design-docs/base/asset-references.md`：key grammar、expansion、Generated Image、candidate API、message/tool boundary 和五条不变量。
- `docs/design-docs/design.md`：系统依赖层级、组合根与模块边界。
- `CONTEXT-MAP.md`、`pixflow-module-file/CONTEXT.md`、`pixflow-conversation/CONTEXT.md`、`pixflow-module-dag/CONTEXT.md`、`pixflow-module-imagegen/CONTEXT.md`：使用 Asset Package、Original Image、Generated Image、Message Reference、Proposal、Canonical DAG、Redraw Proposal 等既定语言。
- `docs/adr/0005-use-canonical-asset-reference-keys.md`：canonical key 的 accepted 系统决策。
- `docs/adr/0006-publish-successful-image-results-as-assets.md`：Generated Image 必须获得新 imageId，不能把 Task result 当素材身份。
- `docs/adr/0007-keep-unconfirmed-proposals-ephemeral.md`：Proposal 不得进入 contracts 共享状态或 durable pending store。
- `docs/design-docs/module/file.md`、`module/dag.md`、`module/imagegen.md`、`module/conversation.md`、`module/task.md` 与 `infra/permission.md`：实施各直接消费者时核对 owner API、深校验、Task 幂等和权限重验。
- `docs/design-docs/exec-plans/completed/contracts-multimodule-implementation-plan.md`：只参考 Enforcer、零依赖与 Maven 验证方式；其中 confirmation-token 目标已经失效，绝不能恢复。
- `docs/design-docs/exec-plans/lint-baseline-remediation-plan.md`：严格 lint、suppression 原子修改和 Maven 串行要求。

## Scope and Non-Goals

范围包括 contracts 的 Asset Reference 值对象、kind、codec、异常语义、测试与 POM 守护；为删除旧 Proposal contracts 和唯一化 reference grammar 所必需的 File、DAG、Imagegen、Conversation、Tools/App 直接适配；对应 Maven 依赖、自动配置、架构守护和最小文档更新。

范围不包括 File 的完整 mention picker/search/pagination、PACKAGE/SKU 数据库 expansion、Generated Image publication、lineage migration、Task 清理语义、Vision job、前端 Materials/Outputs UI、Message Reference 数据库 migration、Permission owner namespace、DAG/Imagegen 业务算法重写或无关格式化。若这些能力尚未实现，本计划只提供它们共同依赖的 key contract 和 owner seam，不伪造端到端完成。

## Target Mechanism

在 `pixflow-contracts/src/main/java/com/pixflow/contracts/asset/` 定义以下语义等价 API。具体文件可按一个 public type 一个文件组织，不能用 nullable component 退回非法组合可构造模型：

    public enum AssetReferenceKind {
        PACKAGE, SKU, IMAGE
    }

    public sealed interface AssetReferenceKey
            permits PackageAssetReferenceKey, SkuAssetReferenceKey, ImageAssetReferenceKey {
        AssetReferenceKind kind();
        long packageId();
    }

    public record PackageAssetReferenceKey(long packageId) implements AssetReferenceKey { ... }

    public record SkuAssetReferenceKey(long packageId, String skuId)
            implements AssetReferenceKey { ... }

    public record ImageAssetReferenceKey(long packageId, long imageId)
            implements AssetReferenceKey { ... }

    public interface AssetReferenceCodec {
        AssetReferenceKey parse(String referenceKey);
        String serialize(AssetReferenceKey reference);
    }

    public final class CanonicalAssetReferenceCodec implements AssetReferenceCodec { ... }

三个 record 的 compact constructor 只做局部不变量：packageId/imageId 必须大于零；skuId 不得为 null、empty 或全空白，但保留原始字符，不 trim。codec 的 `serialize` 按 subtype 生成唯一字符串；`parse` 先识别精确 grammar，再严格解码 SKU，最后重新 serialize 并与原输入逐字比较。任何差异都抛 `IllegalArgumentException` 或 contracts 自有的纯 JDK `InvalidAssetReferenceException extends IllegalArgumentException`。若新增专用异常，它只能携带稳定 reason enum 和脱敏消息，不能依赖 Common 的错误模型，也不能回显整条未可信输入。

SKU codec 按 UTF-8 byte 操作。编码只保留 RFC 3986 unreserved 字符，其他 byte 输出大写 `%HH`。解码拒绝裸 `%`、非十六进制、`+`、空 segment、slash 未编码、多余 segment、overlong/invalid UTF-8、surrogate 和 decode 后重新编码不相等。PACKAGE/IMAGE 数字段拒绝 `+1`、`01`、空、零、负数、空白和 long 溢出。parser 不查询数据库，也不判断 sourceType、删除、owner 或 readiness。

Conversation 的 owner API 形状应语义等价于：

    public interface ProposalService {
        ProposalView publish(PublishProposalCommand command);
        ProposalDecisionResult confirm(String conversationId, String proposalId,
                                       AuthenticatedPrincipal principal);
        void reject(String conversationId, String proposalId,
                    AuthenticatedPrincipal principal);
    }

`PublishProposalCommand`、`ProposalView` 和 pending 状态位于 `com.pixflow.module.conversation` 的公开 application API，不位于 contracts。App 的 `submit_image_plan` 与 `submit_imagegen_plan` adapter 分别调用 DAG/Imagegen 的 validated API，调用当前 `ProposalPublicationAuthorizer` 做二次授权，再构造 Conversation command。Conversation 继续以 toolCallId 吸收重复发布并生成 proposalId。DAG/Imagegen 的 public validated payload 可以携带 canonical bytes/hash、reference keys 和执行所需值，但不得携带 proposal status、expiry、task binding、CAS 或 repository 类型。

## Plan of Work

### Milestone 0：冻结基线、失败合同和 lint 顺序

先记录 `git status --short`，以及 contracts、File permission、DAG/Imagegen Proposal、Conversation Proposal、App 的精确 diff。当前两个 Conversation 测试和 File permission 测试已有用户改动；先阅读并保留它们，不得 restore、删除未跟踪目录或用批量格式化覆盖。

为 contracts 增加 test-scope JUnit 依赖和测试骨架。先写 `CanonicalAssetReferenceCodecTest` 的合法/非法矩阵，以及反射或源码守护测试，要求 contracts 不出现 `proposal` package、不出现 Spring/Jackson/Lombok annotation、公开 key 不可构造非法 kind/字段组合。此时允许测试尚未运行；按用户要求先执行 `mvn -pl pixflow-contracts -am -DskipTests verify`。lint/编译通过后才运行 `mvn -pl pixflow-contracts -am test`，记录预期的失败原因，证明测试能捕获缺失 API 和旧 Proposal package。若新增测试导致 test compilation 在 verify 阶段失败，这仍是前置 lint/compile 证据；先补最小测试夹具到可编译，再运行测试，不能反过来先跑 test。

### Milestone 1：实现唯一 canonical Asset Reference codec

新增 `com.pixflow.contracts.asset` API 和纯 JDK 实现。先完成三个 sealed subtype 的局部不变量，再完成 numeric grammar，最后完成 SKU UTF-8 percent codec 和 reserialize equality。所有集合或 byte array（若实现内部使用）不得从 API 泄漏；codec 实例无状态、线程安全且可复用。

测试至少覆盖 PACKAGE/SKU/IMAGE ASCII round-trip，中文、空格、斜杠、百分号和 `+` 的 SKU round-trip；还要覆盖 null/blank、未知前缀、大小写错误、缺/多 segment、零/负/leading-zero/overflow ID、小写 percent、转义 unreserved 字符、非法 hex、截断 percent、invalid UTF-8、重复 slash 和 trailing slash。增加 property-style 参数化样本，断言对每个可构造 key 都满足 `parse(serialize(key)).equals(key)`，并断言 `serialize(parse(text)).equals(text)` 只对 canonical text 成立。

先运行 contracts lint：

    mvn -pl pixflow-contracts -am -DskipTests verify

只有成功后才运行：

    mvn -pl pixflow-contracts -am test

然后运行 dependency tree 和 import 负向搜索，证明 compile/runtime 仍为零依赖。JUnit 只能是 test scope；不得为方便给 contracts 加 Common、Apache Commons Codec 或 Spring。

### Milestone 2：删除重复 grammar，让 File 成为事实 resolver

在 `AssetPermissionProof` 中注入或持有唯一 `CanonicalAssetReferenceCodec`，删除私有 `Reference` parser。解析异常统一在 File/Permission adapter 边界映射为现有 denied/invalid-reference 结果；contracts 自身不知道 permission。将 DAG 中的 `packageId(referenceKeys)` 手工截取替换为 typed parse，或更进一步从 File preflight 返回的 typed facts 取得 package identity。将 Imagegen 手工字符串拼接替换为 `serialize(new ImageAssetReferenceKey(...))`。

为 File 增加 public typed resolve seam 时，区分三个动作：parse 只验证 grammar；resolve 查询存在性、owner、deleted/sourceType；expand 把 PACKAGE/SKU 变成 processable Original Images，IMAGE 只返回一个 Original/Generated Image。完整 expansion 若属于后端总计划后续里程碑，可以在本计划只落接口和 File permission 的 parser 迁移，但不得用 contracts codec 假装资源已经 resolve。

所有工具输入 schema 开始接受 `referenceKey` 或 `referenceKeys`，不再接受并行 `packageId`、`imageIds` 或 `source_image_ids`。Imagegen 要求一个 concrete IMAGE key；PACKAGE/SKU expansion 和“一图一个 Redraw Proposal、单轮最多 20 个”由 App/File 边界按 `asset-references.md` 执行。若本里程碑不具备完整 Imagegen expansion 前置能力，保持 feature 未接通并在 Progress 记录，不能恢复旧字段作为 fallback。

每个受影响批次先运行：

    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-module-dag,pixflow-module-imagegen -am -DskipTests verify

lint 成功后才运行同范围 `test`。测试证明 wrong-kind 在具体工具边界被拒绝、File proof 不再私自解释另一套 grammar、手工拼接/拆分搜索无生产命中。

### Milestone 3：把 Proposal 送回 Conversation 所有权

先在 DAG 增加或收窄 validated API：输入 tool arguments 和 typed references，完成 schema、DAG validation、Canonical DAG、hash 与 material preflight，返回 immutable `ValidatedImagePlan`。它不生成 proposalId、不持有 publication port。Imagegen 同样返回 immutable `ValidatedRedrawRequest`，保证恰好一个 source IMAGE、prompt/params 规范化、payload hash 稳定。先用现有测试证明相同输入输出相同 canonical payload/hash。

在 Conversation 公开 application package 中定义 `ProposalService` 与 owner-owned command/view。把 `PendingProposalRepository` 从 `ProposalPublicationPort` implementation 改为 Conversation 内部 repository，由 service 负责 command 校验、toolCallId 幂等、proposalId 分配、ephemeral 生命周期和事件发布。不要让 repository、ConcurrentMap、status CAS 或 payload JSON mapper 穿出公开 API。

把 `SubmitImagePlanHandler` 和 Imagegen 对应的工具编排适配移动或重建到 `pixflow-app/src/main/java/com/pixflow/app/proposal/`。适配器调用 producer validated API，再调用 invocation 中的可信 publication authorizer，最后调用 `ProposalService.publish`。它只做组合与 DTO 投影，不复制 DAG/Imagegen 深校验或 Conversation 幂等。迁移 tool descriptor/auto-configuration/sentinel tests，确认每个工具只注册一次。

消费者全部编译后删除 `pixflow-contracts/src/main/java/com/pixflow/contracts/proposal/`，从 DAG、Imagegen、Conversation、Agent、Task 和 App POM 中删除不再真实使用的 contracts 依赖；真正使用 Asset Reference key 的模块可以保留。更新注释中的“确认队列”“planId”“source_image_ids”等过期语言，不改无关业务。

严格按以下顺序串行验证：

    mvn -pl pixflow-contracts,pixflow-module-dag,pixflow-module-imagegen,pixflow-conversation,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-contracts,pixflow-module-dag,pixflow-module-imagegen,pixflow-conversation,pixflow-app -am test

如果测试失败，修复后再次先运行第一条 lint 命令，再运行第二条测试。不得新增旧 interface、临时构造器重载或 App/Conversation 双 publisher 让编译变绿。

### Milestone 4：架构守护、全量验证和交接

增加可维护守护：contracts POM 保持零 compile/runtime dependencies；源码只允许 `java.*` import；contracts package 不出现 `proposal`、`confirmation`、owner service 或 framework annotation；DAG/Imagegen 不依赖 Conversation；Proposal tool adapters 只在 App；Conversation 不依赖 contracts Proposal；File 是唯一 resource resolver。更新 `docs/design-docs/base/contracts.md` 只在实现名称需要细化时修改，不降低现有规则。同步后端总计划 Milestone 0/1 的 Progress 和交接，但不覆盖总计划已有用户修改。

最终先运行全仓 lint，再运行全仓测试：

    mvn -DskipTests verify
    mvn verify

两条命令之间不得插入生产修改；若 lint 后必须修改代码，重新运行 lint。最后运行负向搜索、`git diff --check` 和 `git status --short`。Maven reactor 必须串行。Docker/Testcontainers 失败时记录 `docker info`、首个失败测试和首个业务栈帧；contracts 自身没有 Docker 测试，不能因全仓环境阻断而跳过它的纯合同测试。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。开始和每个停止点运行：

    git status --short
    git diff --check
    rg --files docs/design-docs/exec-plans | rg -v "completed[\\/]"

当前实现与目标 API 搜索：

    rg -n "ProposalDraft|ProposalPublicationPort|com\.pixflow\.contracts\.proposal" pixflow-contracts pixflow-module-dag pixflow-module-imagegen pixflow-conversation pixflow-app
    rg -n "package:.*image:|substring\(\"package:|split\(\"/\"|source_image_ids|imageIds|packageId" pixflow-module-file pixflow-module-dag pixflow-module-imagegen
    rg -n "AssetReference(Key|Kind|Codec)|CanonicalAssetReference|PackageAssetReferenceKey|SkuAssetReferenceKey|ImageAssetReferenceKey" .

完成后第一条生产源码预期无输出。第二条必须逐项判读：数据库实体和 owner-internal packageId 可以保留；工具跨边界旧输入、reference 手工 parser/serializer 和独立 imageIds 不得保留。第三条应命中 contracts 唯一实现、测试及显式消费者。

contracts 每次验证都先 lint 后测试：

    mvn -pl pixflow-contracts -am -DskipTests verify
    mvn -pl pixflow-contracts -am test
    mvn -pl pixflow-contracts dependency:tree
    rg -n "import (com\.pixflow|org\.springframework|com\.fasterxml|lombok)" pixflow-contracts/src

dependency tree 允许 JUnit 的 test-scope 依赖，不允许任何 compile/runtime 项目依赖。import 搜索预期无输出；测试源码也应只用 JDK 与 JUnit，不使用 Spring test。

触达模块最终验证同样先 lint 后测试：

    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-module-dag,pixflow-module-imagegen,pixflow-conversation,pixflow-app -am -DskipTests verify
    mvn -pl pixflow-contracts,pixflow-module-file,pixflow-module-dag,pixflow-module-imagegen,pixflow-conversation,pixflow-app -am test

全仓最终验证：

    mvn -DskipTests verify
    mvn verify
    git diff --check
    git status --short

## Validation and Acceptance

Asset Reference 语法验收要求三种 key 的构造、serialize、parse 和 equality 稳定。任何可构造 key 都能 round-trip；任何 parse 成功文本都已经是唯一 canonical text。数值 ID 为正 long；SKU 编码不受默认 locale 或默认 charset 影响；并发调用无共享可变状态。非法输入只产生稳定、脱敏、纯 JDK 异常，不泄漏 storage key 或数据库信息。

边界验收要求 Web/Agent 只回传 backend-produced key；File 重新检查存在性、owner、deleted、kind 和 readiness；wrong-kind 工具请求被拒绝；PACKAGE/SKU expansion 不包含 Generated Image；多个引用只按完全相同 referenceKey 去重，实际执行再按 `(packageId,imageId)` 去重。若 expansion 尚未由本计划实现，应有接口/测试明确标记缺口，而不是在客户端枚举。

Proposal 验收要求 contracts 中没有 Proposal 类型。DAG 与 Imagegen 可以在不读取 Conversation 内部包的情况下产生 validated payload；App adapter 才把 payload 发布给 Conversation。同一 toolCallId 重放得到同一 proposalId；未知 proposal type 被拒绝；拒绝不创建 Task；confirm 的 durable replay 仍以 proposalId 返回同一 Task。Pending Proposal 不进入 MySQL、Redis、transcript 或 contracts。

依赖验收要求 contracts 没有 compile/runtime dependency、framework annotation 或自动配置。DAG/Imagegen 不为发布 Proposal 依赖 Conversation；App 是跨 owner 编排位置。删除无真实 import 的 contracts POM 依赖，不能仅凭“以后可能使用”保留。

门禁验收要求每一组测试都有时间上更早、范围不小于该测试的成功 lint 记录。`mvn -DskipTests verify` 只记为 Checkstyle/SpotBugs/编译通过，绝不写成测试通过。最终 `mvn verify` 成功，或以测试名、跳过数、环境证据和首个业务栈帧准确记录范围外阻断。

## Idempotence and Recovery

codec、搜索、lint 和测试命令可重复运行且不修改生产数据。parser 无外部状态，不需要清理。App/Conversation 的 Proposal 测试使用进程内 fixture 和固定 Clock/ID supplier，不能依赖真实 Redis 或 MySQL 才证明 toolCallId 幂等。

不得执行 `git reset --hard`、`git checkout --`、整仓 restore 或删除用户工作。当前 Conversation/File 测试已有修改；若与本计划重叠，先理解现有断言并做最小合并。Java 文件与其 Checkstyle suppression 是原子恢复单位；本计划原则上不新增 suppression，发现 lint 问题直接修源码。

Proposal 切换的恢复单位是“producer validated API + App adapter + Conversation owner API + 删除 contracts 旧包”。中途停止时可以留在未提交工作树继续，但任何提交都不能让旧/新 publisher 同时成为生产 Bean。若删除旧包后编译失败，按 compiler error 迁移真实调用方，不恢复 deprecated bridge。

SKU codec 不进行数据库 migration。若未来发现历史 SKU 含 Java 无法表示的非法 Unicode 或业务要求另一套 normalization，先在本文 `Surprises & Discoveries` 与 `Decision Log` 记录证据并更新 ADR；不能静默改变 canonical encoding，因为这会改变资源身份。

## Artifacts and Notes

目标依赖与所有权：

    pixflow-contracts
      -> pure AssetReferenceKey + AssetReferenceCodec
      -> no internal/framework runtime dependencies

    File owner facts
      -> parse with contracts codec
      -> resolve / inspect / expand / permission proof

    DAG validated payload -----\
                                -> App tool adapter -> Conversation ProposalService
    Imagegen validated payload -/                       -> ephemeral repository
                                                        -> confirm/reject
                                                        -> Task idempotency by proposalId

明确禁止的终态：

    contracts.proposal.*
    contracts.confirmation.*
    nullable catch-all AssetReference record
    hand-built "package:" + id strings outside codec
    client/tool parallel packageId + imageIds + source_image_ids identities
    Proposal status/repository/CAS in contracts, DAG, Imagegen, or App

## Interfaces and Dependencies

`pixflow-contracts` 最终唯一业务包是 `com.pixflow.contracts.asset` 或语义等价的 `reference` 包。若实现者选择不同包名，必须在 Decision Log 说明，并保证全仓只有一个 authoritative package。模块只增加 JUnit test-scope 依赖；Enforcer 继续在 validate 阶段禁止所有内部和 framework 依赖。

File 公开 API 使用 typed key 或原始 canonical string 加立即 parse 的窄入口，不让 storage location 穿过边界。推荐语义形状：

    public interface AssetReferenceResolver {
        ResolvedAssetReference resolve(AssetReferenceKey key, AssetAccessMode mode);
        List<ResolvedImageReference> expand(AssetReferenceKey key, AssetAccessMode mode);
        AssetReferenceView inspect(AssetReferenceKey key);
    }

这些类型属于 File，不属于 contracts，因为它们包含 current facts、sourceType、display path、readiness 或 lineage。`AssetReferenceView.referenceKey` 必须由 contracts codec 序列化，不从数据库读取独立 mutable key。

DAG 与 Imagegen 的最终公开 payload 名称可按各自设计微调，但所有权必须清晰：

    public record ValidatedImagePlan(
            byte[] canonicalPayload,
            String payloadHash,
            List<AssetReferenceKey> references) { ... }

    public record ValidatedRedrawRequest(
            ImageAssetReferenceKey source,
            String prompt,
            Map<String, Object> parameters,
            String payloadHash) { ... }

数组、集合和 map 必须 defensive copy。`ValidatedImagePlan` 不包含 proposalId/status；`ValidatedRedrawRequest` 只有一个 IMAGE source，不包含 source image list。若 JSON 序列化仍由 producer 完成，App 只携带 producer 返回的 canonical bytes/string，不重新 hash。

Conversation owner API 可使用 opaque payload 加受控 enum，但 command 必须位于 Conversation 模块，并在 constructor 防御性复制：

    public record PublishProposalCommand(
            PendingProposalType type,
            String conversationId,
            String toolCallId,
            String canonicalPayload,
            String payloadHash,
            List<String> referenceKeys,
            Instant createdAt) { ... }

这里的 `referenceKeys` 必须来自 contracts codec；Conversation 保存它们用于显示和 confirm 重验，但不解析 File 内部实体。App adapter 把 typed key serialize 后传入，Conversation confirm 再调用 File/Permission proof 获取当前事实。

## Revision Notes

2026-07-17 / Codex: 创建本计划。依据最新后端架构总计划、`base/contracts.md`、Asset Reference 规范、Context Map 和 ADR 0005/0006/0007，确认当前 contracts 只剩错误归属的 Proposal DTO，且 File/DAG/Imagegen 存在三套手工 key 解释。计划采用 sealed typed keys + 严格 UTF-8 canonical codec，把 Proposal 编排移到 App 与 Conversation owner API，删除旧共享包和无效依赖；所有验证严格执行“先 `-DskipTests verify` lint，后 test”。

2026-07-17 / Codex: 开始执行 Milestone 0。依据用户指定的 `$implement` 技能采用公共 seam TDD，先加入 test-scope JUnit 和 codec 缺失合同；保留 Conversation/File 现有用户修改，且继续执行每轮测试前先 lint 的门禁。

2026-07-17 / Codex: 完成 Milestone 0 至 3 并进入 Milestone 4。一次性删除旧 Proposal 与 Imagegen 多源路径，以 App adapter + Conversation owner service 替代共享 publisher；contracts 增加 Enforcer 与纯源码架构守护。同步记录 File parser 漂移、incremental compiler 发现和范围外 Loop lint 基线，明确不扩大宣称 File 完整 resolver/publication 已完成。

2026-07-17 / Codex: 完成 Milestone 4。依据 `$implement` 触发的 Standards/Spec 双轴 review，继续删除多源配置/错误码与字符串 hasher 重载，收紧 Imagegen identity 不变量，并把 Proposal 内部 entity/status/CAS/future 隐藏在只读 snapshot + proposalId command API 后；另以 App 源码守护固定 tool adapter 与 resolver owner。最终 7 模块 359 项测试通过；全仓 lint 的 Loop Checkstyle 与 DAG SpotBugs 范围外基线已准确记录，未为通过门禁扩大修改范围。

# 重构待确认提案所有权，消除 PendingPlanPort 装配脆弱性

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划按仓库根目录 `PLANS.md` 维护。后续执行者只需要当前工作树和本文，就能理解为什么要重构 `PendingPlanPort`，哪个已有共享模块适合承载共享契约，哪些旧代码必须删除，如何验证重构后 PixFlow app 不再因为 `PendingPlanPort` 自动装配顺序失败而启动中断。

## Purpose / Big Picture

当前启动失败的直接现象是 `ImagegenAutoConfiguration#imagegenPlanService(...)` 找不到 `PendingPlanPort` Bean。深层原因不是少写一个 Bean，而是待确认提案的所有权被拆散：`PendingPlanPort` 接口在 `pixflow-module-imagegen`，实现 `PendingPlanPortAdapter` 在 `pixflow-conversation`，真实表 `pending_plan` 与 mapper 又在 `pixflow-module-dag`。这让 Spring Boot 自动配置顺序、`@ConditionalOnBean` 判断和 Maven 依赖方向都变得脆弱。

完成本计划后，Agent 仍然只能通过 `submit_image_plan` / `submit_imagegen_plan` 生成待确认提案，前端仍然通过现有 `/api/conversations/{conversationId}/confirm/{proposalId}/challenge` 和 `/submit` 完成确认；但是后端内部的提案共享边界会稳定下来：纯 Java 契约放入现有共享模块 `pixflow-contracts`，具体 `pending_plan` 持久化实现放在已经拥有该表的 `pixflow-module-dag`。`pixflow-conversation` 不再替 `imagegen` 实现 port，`imagegen` 不再定义自己的待确认提案 SPI，启动时不再依赖 conversation 先装配出一个跨模块 adapter。

可观察结果是：运行 `mvn -pl pixflow-module-dag,pixflow-module-imagegen,pixflow-conversation -am test` 与 `mvn -pl pixflow-app -am test` 时，Spring 上下文中能稳定存在唯一 `PendingPlanPort` Bean；`submit_imagegen_plan` 能入同一张 `pending_plan` 表；确认端点能按同一个 `proposalId` 查回 DAG 与 imagegen 两类提案；代码库中不再存在 `com.pixflow.module.imagegen.port.PendingPlanPort`、`PendingPlanProposal` 或 `com.pixflow.module.conversation.proposal.PendingPlanPortAdapter`。

## Progress

- [x] (2026-07-06 23:40+08:00) 阅读 `PLANS.md`、`docs/design-docs/index.md` 和当前活跃执行计划 `backend-contract-inconsistency-fix-plan.md`、`frontend-contract-inconsistency-fix-plan.md`、`infra-ai-clients-completion-plan.md`。
- [x] (2026-07-06 23:50+08:00) 检查 `docs/design-docs/base/contracts.md`、`base/common.md`、`harness/state.md`、`module/dag.md`、`module/imagegen.md`、`module/conversation.md` 与 `docs/design-docs/frontend/api.md`，确认共享模块准入规则和前端 API 边界。
- [x] (2026-07-06 23:58+08:00) 搜索源码，确认 `PendingPlanPort` 当前接口在 imagegen，唯一实现类在 conversation，真实 `pending_plan` 表、实体、mapper 和 `PendingPlanService` 在 dag。
- [x] (2026-07-07 00:05+08:00) 判定适合的已有共享位置：纯契约进入 `pixflow-contracts`；持久化实现留在已有 `pixflow-module-dag`，不新建模块，不把 DB/Spring 代码放进 `contracts`。
- [x] (2026-07-07 00:10+08:00) 撰写本 ExecPlan，记录机制、迁移步骤、删除旧代码要求、测试与前端 API 影响。
- [x] (2026-07-07 00:45+08:00) 实施代码重构：新增 `contracts.proposal.PendingPlanPort` / `PendingPlanProposal`，dag 新增 `PendingPlanPortAdapter` 并在 `DagAutoConfiguration` 暴露唯一生产 Bean，imagegen 改为消费 contracts SPI，conversation 删除旧 adapter 和 `PendingPlanPort` Bean。
- [x] (2026-07-07 01:05+08:00) 补充/更新测试：新增 dag adapter 行为单测，更新 imagegen 服务、handler、confirmation support、自动配置哨兵测试，更新 dag ArchUnit 规则为允许 `contracts.proposal`、禁止 `contracts.confirmation`。
- [x] (2026-07-07 01:20+08:00) 更新设计文档：同步 `base/contracts.md`、`module/dag.md`、`module/imagegen.md`、`module/conversation.md` 中关于 pending plan 所有权的过时描述。
- [x] (2026-07-07 00:35+08:00) 运行 Maven 验证：`pixflow-contracts`、`pixflow-module-dag,pixflow-module-imagegen -am`、`pixflow-conversation -am`、`pixflow-app -am` 全部测试通过，未再出现 `No qualifying bean of type 'PendingPlanPort'`。

## Surprises & Discoveries

- Observation: `pixflow-contracts` 是零依赖叶子，适合放 SPI / record / enum，但不适合放 Spring Bean、MyBatis mapper 或 `pending_plan` 持久化实现。
  Evidence: `pixflow-contracts/pom.xml` 的 enforcer 禁止 `com.pixflow:*`、Spring、Jackson、Lombok 等依赖；`docs/design-docs/base/contracts.md` 明确 contracts 只放纯形状、不放行为和基础设施实现。

- Observation: `pixflow-state` 是共享 harness 模块，但它的设计边界是任务运行态读模型和恢复协调，不拥有业务表，也不直连 MySQL。
  Evidence: `docs/design-docs/harness/state.md` 明确 state 不拥有业务表、通过 `CheckpointReadPort` 读 `process_*` checkpoint；`pixflow-state/pom.xml` 没有 MyBatis 依赖。

- Observation: `pixflow-module-dag` 已经拥有 `pending_plan` 表、实体、mapper、migration 和 `PendingPlanService`，并且设计文档原本就把 pending plan 描述为对 `IMAGE_PLAN / IMAGEGEN` 两类提案中立。
  Evidence: `pixflow-module-dag/src/main/resources/db/migration/V1__create_pending_plan.sql` 注释写明该表对 `IMAGE_PLAN | IMAGEGEN` 两类提案中立；`PendingPlan`、`PendingPlanMapper`、`PendingPlanService` 均在 `pixflow-module-dag`。

- Observation: 当前代码和设计文档发生了交叉漂移。`module/imagegen.md` 说 `PendingPlanPort` 由 conversation 实现；`module/conversation.md` 又说 conversation 不写 pending 表；当前源码确实由 conversation 写入 dag 的 mapper，导致边界互相打架。
  Evidence: `pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/port/PendingPlanPort.java` 定义 port；`pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingPlanPortAdapter.java` 实现它并调用 `PendingPlanMapper.insert(...)`。

- Observation: 本重构不要求改变浏览器前端 REST/SSE/WS 契约。
  Evidence: `docs/design-docs/frontend/api.md` 将 Agent 工具 schema、Java SPI、MQ consumer 和 provider adapter 明确列为非前端 API；本计划只移动 Java SPI 和持久化所有权，确认端点路径、请求体、响应体保持不变。

- Observation: dag 原有 ArchUnit 守护把 `contracts` 整体列为禁用依赖，但本重构需要 dag 实现 `contracts.proposal.PendingPlanPort`。
  Evidence: `pixflow-module-dag/src/test/java/com/pixflow/module/dag/architecture/DagArchitectureTest.java` 已改为只禁止 `com.pixflow.contracts.confirmation..`，允许 `contracts.proposal` 作为纯 pending-plan SPI。

- Observation: 企图在 dag 模块新增同时加载 imagegen 的自动配置测试会破坏 Maven 模块依赖方向。
  Evidence: dag 模块不能导入 `pixflow-module-imagegen` 测试类；该临时测试已删除，保留 dag adapter 单测、imagegen 自动配置哨兵测试和 Maven reactor 验证。

- Observation: 当前 `pending_plan` 数据库唯一键和 adapter 幂等实现按 `toolCallId` 去重，不是 `(conversationId, toolCallId)` 联合去重。
  Evidence: `PendingPlanPortAdapter` 使用 `PendingPlanMapper.findByToolCallId(...)`；本重构不扩大数据库行为，只在文档/注释中记录未来如需避免跨会话碰撞应另起 migration。

## Decision Log

- Decision: 共享契约放入现有 `pixflow-contracts`，不放入 `pixflow-common`。
  Rationale: `common` 承载错误、分页、响应信封、脱敏等人人依赖的横切能力；`contracts` 的定位是为会形成依赖环的兄弟模块提供纯 SPI / record / enum。`PendingPlanPort` 正是 imagegen、dag、conversation 之间的解环契约，且可以保持零 Spring / 零 DB / 零行为实现。
  Date/Author: 2026-07-07 / Codex

- Decision: `pending_plan` 的具体持久化实现保留并收敛到 `pixflow-module-dag`，不迁入 `pixflow-contracts`、`pixflow-state` 或 `pixflow-session`。
  Rationale: `pixflow-module-dag` 已经拥有 `pending_plan` 表、mapper、状态机和 migration；`contracts` 不能带 Spring/MyBatis，`state` 不拥有业务表，`session` 只负责 transcript。把实现留在 dag 是对现有模块所有权的最小且正确收敛。
  Date/Author: 2026-07-07 / Codex

- Decision: 本重构不采用迁移式兼容层，不保留旧 `imagegen.port.PendingPlanPort`、旧 `PendingPlanProposal` 或 conversation 的 `PendingPlanPortAdapter`。
  Rationale: 用户明确要求不能为了迁移式安全保留旧代码。保留旧接口会让两个 port 共存，后续继续出现自动配置、Bean 冲突或误用旧包的问题。重构必须一次性改完所有 import 和测试。
  Date/Author: 2026-07-07 / Codex

- Decision: 不新增 `pixflow-proposal` Maven 模块。
  Rationale: 从纯架构角度看独立 proposal 模块很干净，但用户要求先检查适合放入哪个已有共享模块。现有 `contracts + dag` 的组合已经能解决当前环和装配问题；新增模块会扩大 reactor、dependencyManagement 和文档变更面，不是当前最小彻底方案。
  Date/Author: 2026-07-07 / Codex

- Decision: 前端 API 文档本轮不需要同步改字段或路由，但计划必须记录“无前端契约变化”。
  Rationale: 前端只看 confirmation REST 与 SSE 事件；本重构移动 Java SPI 和内部持久化实现，不改变 `/challenge`、`/submit`、`ConfirmationSubmitResponse`、SSE proposal 展示数据或 task 跳转行为。如果实施中发现必须改变前端 payload，必须先更新 `docs/design-docs/frontend/api.md` 再改代码。
  Date/Author: 2026-07-07 / Codex

## Outcomes & Retrospective

本计划已完成并通过 Maven 验证。`PendingPlanPort` 从 imagegen 私有 port 升级为 `contracts.proposal` 中的共享提案契约；dag 成为唯一 `pending_plan` 持久化实现提供方；imagegen 不再定义自己的 pending-plan SPI；conversation 只做确认 REST 编排和提案读视图，不再替其他模块写 pending 表。浏览器前端 REST/SSE/WS 契约未变化，因此未修改 `docs/design-docs/frontend/api.md`。验证命令覆盖 `mvn -pl pixflow-contracts test`、`mvn -pl pixflow-module-dag,pixflow-module-imagegen -am test`、`mvn -pl pixflow-conversation -am test` 与 `mvn -pl pixflow-app -am test`。

## Context and Orientation

PixFlow 是 Spring Boot 3 + Maven 多模块项目。相关模块的当前位置如下。

`pixflow-contracts` 是共享契约模块，包根为 `com.pixflow.contracts`。它已经包含 `contracts.confirmation` 子包，例如 `ConfirmationToken`、`TokenClaims`、`ConfirmationTokenStore`。它的 POM 禁止引入 Spring、Jackson、内部模块等依赖，因此只能放 interface、record、enum、常量和轻量构造不变量。

`pixflow-module-dag` 是确定性 DAG 模块，包根为 `com.pixflow.module.dag`。它当前拥有 `pending_plan` 表：`pixflow-module-dag/src/main/resources/db/migration/V1__create_pending_plan.sql`；实体：`pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/PendingPlan.java`；mapper：`PendingPlanMapper.java`；服务：`PendingPlanService.java`。这张表已经用 `type` 字段区分 `IMAGE_PLAN` 和 `IMAGEGEN`，本计划要求继续把它作为两类待确认提案的唯一事实源。

`pixflow-module-imagegen` 是生成式产图模块，包根为 `com.pixflow.module.imagegen`。实施前它定义了 `com.pixflow.module.imagegen.port.PendingPlanPort` 和 `PendingPlanProposal`，并在 `ImagegenPlanService`、`ImagegenConfirmationSupport` 中注入它们。这是问题的源头之一：imagegen 拥有了一个并非 imagegen 私有的跨路径提案端口。实施后这两个类型已删除，imagegen 改为依赖 `contracts.proposal`。

`pixflow-conversation` 是对话与确认 REST 边界模块，包根为 `com.pixflow.module.conversation`。实施前它有 `PendingPlanPortAdapter implements com.pixflow.module.imagegen.port.PendingPlanPort`，实现时直接调用 dag 的 `PendingPlanMapper` 写 `pending_plan`。这让 conversation 变成 imagegen 的提案存储提供方，也与 conversation 只负责确认边界的设计不一致。实施后该 adapter 已删除，conversation 只通过 `PendingProposalRepository` 读取确认事实。

`pixflow-app` 是启动应用。应用不再扫描整个 `com.pixflow` 根包，而是依靠各模块的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 发现自动配置。因此跨模块 Bean 不应靠普通组件扫描或条件装配碰运气，核心能力要由属主模块稳定暴露。

本文使用的术语如下。待确认提案是 Agent 工具提交给后端、等待用户确认的计划，不会直接触发真实图片处理。SPI 是 Java 接口形式的依赖倒置接缝，定义方不关心实现方是谁。自动配置是 Spring Boot 读取各 jar 的 `AutoConfiguration.imports` 后创建 Bean 的机制。迁移式兼容层是为了同时支持旧包和新包而保留 adapter、桥接接口或废弃类；本计划明确禁止这种做法。

## Plan of Work

第一阶段移动纯契约。编辑 `pixflow-contracts`，新增子包 `com.pixflow.contracts.proposal`。把当前 `pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/port/PendingPlanPort.java` 和 `PendingPlanProposal.java` 的语义迁入 contracts，命名保持清晰：`PendingPlanPort` 和 `PendingPlanProposal`。`PendingPlanProposal` 仍是 record，字段至少包含 `planType`、`payloadJson`、`conversationId`、`packageId`、`toolCallId`、`createdAt`。如果实施者发现 `schemaVersion`、`payloadHash` 或 `expiresAt` 需要跨模块共享，可以加字段，但必须保持纯 JDK 类型，不引入 Jackson 或 Spring 注解。迁入后删除 imagegen 原包下的旧两个文件，不保留 deprecated wrapper。

第二阶段让 dag 成为唯一 port 实现提供方。编辑 `pixflow-module-dag/pom.xml`，增加对 `pixflow-contracts` 的依赖。新增或改造 dag 侧实现类，例如 `pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/PendingPlanPortAdapter.java`，实现 `com.pixflow.contracts.proposal.PendingPlanPort`。该 adapter 使用现有 `PendingPlanMapper` 和 `PendingPlanService` 写入 / 读取 `pending_plan`。对于 `IMAGE_PLAN`，继续走 `PendingPlanService.enqueue(...)` 的 DAG 深校验和 canonical hash；对于 `IMAGEGEN`，写入 `PendingPlan` 时必须设置 `type="IMAGEGEN"`，`dagJson` 字段作为中立 `payloadJson` 使用，`payloadHash` 使用 imagegen 自己传入或 adapter 统一对 `payloadJson` 做 SHA-256，`schemaVersion` 使用 `"1"` 或明确的 proposal schema 版本。不要把 imagegen 的校验逻辑塞进 dag adapter；imagegen handler 仍负责生图提案校验。

第三阶段在 `DagAutoConfiguration` 暴露唯一 `PendingPlanPort` Bean。编辑 `pixflow-module-dag/src/main/java/com/pixflow/module/dag/config/DagAutoConfiguration.java`，新增 `@Bean @ConditionalOnMissingBean public PendingPlanPort pendingPlanPort(...)`。该 Bean 的直接参数应是 `PendingPlanMapper`、`PendingPlanService`、`DagProperties`、`ObjectMapper`、`Clock` 等真实必需依赖，不要给核心 Bean 加 `@ConditionalOnBean(PendingPlanMapper.class)` 这种会静默跳过的条件。若缺少 mapper，应让 Spring 在真实依赖处失败，而不是让 imagegen 后续报“没有 port”。

第四阶段改 imagegen 依赖 contracts。编辑 `pixflow-module-imagegen/pom.xml`，保留或确认已有 `pixflow-contracts` 依赖。把 `ImagegenPlanService`、`ImagegenConfirmationSupport`、测试 fake port 等所有 import 从 `com.pixflow.module.imagegen.port.PendingPlanPort` / `PendingPlanProposal` 改为 `com.pixflow.contracts.proposal.*`。删除 `pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/port/PendingPlanPort.java` 和 `PendingPlanProposal.java`。这个删除是本重构的重要验收点，不允许留下继承新接口的旧同名类型。

第五阶段清理 conversation 的错误所有权。删除 `pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingPlanPortAdapter.java`。编辑 `ConversationAutoConfiguration`，删除 `pendingPlanPort(PendingPlanMapper)` Bean 方法。`PendingProposalRepository` 可以继续读取 `PendingPlanMapper` 和 `PendingPlanService` 来构造确认读视图，因为确认边界需要读取提案事实并重算 `expectedCount`；但 conversation 不再实现 imagegen 的提案写入端口。若 `PendingProposalRepository` 上仍有 `@ConditionalOnBean(PendingPlanMapper.class)` 这类核心条件，应按已有自动配置边界原则移除或改成直接参数依赖，避免同类问题复发。

第六阶段整理自动配置顺序。`DagAutoConfiguration` 应在 imagegen 之前提供 `PendingPlanPort`。推荐在 `ImagegenAutoConfiguration` 上声明 `@AutoConfiguration(after = DagAutoConfiguration.class)`，或在 `DagAutoConfiguration` 上声明合适的 `before = ImagegenAutoConfiguration.class`。更清晰的做法是让 imagegen 显式 after dag，因为 imagegen 的提案侧依赖 pending plan 存储。conversation 则 after dag、imagegen、permission、task，因为它负责确认编排和任务创建。顺序声明不能替代正确的依赖归属，但可以让条件报告更可读。

第七阶段补测试。新增一个跨模块装配测试，优先放在 `pixflow-app/src/test/java` 或 `pixflow-module-imagegen/src/test/java` 的 context runner 中，加载 `DagAutoConfiguration`、`ImagegenAutoConfiguration` 和必要 fake `SourceImageReader`、`MeterRegistry`、`ObjectMapper`、`Clock`、MyBatis mapper 替身。断言上下文中存在唯一 `PendingPlanPort`，存在 `ImagegenPlanService`，存在 `submitImagegenPlanDescriptor`。同时更新 imagegen 单元测试中的 fake port import。新增 ArchUnit 或简单 `rg` 守护测试，断言 `com.pixflow.module.imagegen.port.PendingPlanPort` 不再存在、conversation 不再实现 `PendingPlanPort`。

第八阶段同步设计文档。更新 `docs/design-docs/base/contracts.md`，新增 `proposal` 主题，解释它进入 contracts 的理由：imagegen 需要提交提案、dag 拥有持久化实现、conversation 需要确认读取，三者不应互相反向依赖。更新 `docs/design-docs/module/dag.md`，明确 dag 不只拥有 DAG 提案，也提供 `pending_plan` 的中立 port 实现。更新 `docs/design-docs/module/imagegen.md`，把“PendingPlanPort 由 conversation 实现”改成“契约在 contracts，持久化 port 实现在 dag”。更新 `docs/design-docs/module/conversation.md`，删除“conversation 实现 PendingPlanPort / 不写 pending 表”之间的矛盾，改为“conversation 只读 pending_plan 并做确认编排”。如果实施过程中改变任何浏览器可见字段或路由，必须同步更新 `docs/design-docs/frontend/api.md`；按本计划的目标，不应改变前端 API。

## Concrete Steps

从仓库根目录 `D:\study\PixFlow` 开始，先确认工作树，避免覆盖别人改动：

    git status --short

搜索当前旧接口和实现：

    rg -n "com\\.pixflow\\.module\\.imagegen\\.port\\.PendingPlanPort|PendingPlanProposal|implements PendingPlanPort|pendingPlanPort\\(" --glob "*.java" --glob "pom.xml"

第一组编辑 contracts：

    pixflow-contracts/src/main/java/com/pixflow/contracts/proposal/PendingPlanPort.java
    pixflow-contracts/src/main/java/com/pixflow/contracts/proposal/PendingPlanProposal.java

预期完成后，`pixflow-contracts` 仍然只有 JDK 依赖。不要向 `pixflow-contracts/pom.xml` 添加 Spring、Jackson、common、dag、imagegen 或 conversation 依赖。

第二组编辑 dag：

    pixflow-module-dag/pom.xml
    pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/PendingPlanPortAdapter.java
    pixflow-module-dag/src/main/java/com/pixflow/module/dag/config/DagAutoConfiguration.java
    pixflow-module-dag/src/test/java/.../PendingPlanPortAdapterTest.java

预期完成后，dag 是唯一提供 `PendingPlanPort` Bean 的模块，且该 Bean 不被 `@ConditionalOnBean(PendingPlanMapper.class)` 静默隐藏。

第三组编辑 imagegen：

    pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/proposal/ImagegenPlanService.java
    pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/confirm/ImagegenConfirmationSupport.java
    pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/config/ImagegenAutoConfiguration.java
    pixflow-module-imagegen/src/test/java/...

删除：

    pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/port/PendingPlanPort.java
    pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/port/PendingPlanProposal.java

预期完成后，`rg -n "module\\.imagegen\\.port\\.PendingPlan" pixflow-module-imagegen pixflow-conversation pixflow-module-dag pixflow-app` 无结果。

第四组编辑 conversation：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/config/ConversationAutoConfiguration.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingProposalRepository.java

删除：

    pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingPlanPortAdapter.java

预期完成后，conversation 没有任何 `implements PendingPlanPort`，也没有任何 `@Bean PendingPlanPort`。

第五组同步文档：

    docs/design-docs/base/contracts.md
    docs/design-docs/module/dag.md
    docs/design-docs/module/imagegen.md
    docs/design-docs/module/conversation.md

若前端确认接口不变，`docs/design-docs/frontend/api.md` 不需要改动字段；但如果实施者改了 `/challenge`、`/submit`、`ConfirmationChallengeResponse`、`ConfirmationSubmitResponse`、SSE proposal payload 或 taskId 挂载行为，必须同步编辑该文件并补前端 adapter 测试。

完成编辑后依次运行：

    mvn -pl pixflow-contracts test
    mvn -pl pixflow-module-dag,pixflow-module-imagegen -am test
    mvn -pl pixflow-conversation -am test
    mvn -pl pixflow-app -am test

如果全量 app 测试耗时过长，可以先运行：

    mvn -pl pixflow-app -am -DskipTests compile

但最终验收必须至少跑过包含新增装配测试的 Maven 命令。

## Validation and Acceptance

代码结构验收：运行以下搜索，预期没有旧接口和旧 adapter：

    rg -n "com\\.pixflow\\.module\\.imagegen\\.port\\.PendingPlanPort|com\\.pixflow\\.module\\.imagegen\\.port\\.PendingPlanProposal|PendingPlanPortAdapter" --glob "*.java"

预期只允许出现新包：

    com.pixflow.contracts.proposal.PendingPlanPort
    com.pixflow.contracts.proposal.PendingPlanProposal
    com.pixflow.module.dag.propose.PendingPlanPortAdapter

Bean 装配验收：跨模块 context runner 断言 `PendingPlanPort` Bean 数量为 1，且其实现类来自 `pixflow-module-dag`。同一上下文中 `ImagegenPlanService` 和 `submitImagegenPlanDescriptor` 必须能创建。这个测试在重构前应失败或需要 fake port；重构后不需要 fake port 也能证明真实 app 接线完整。

行为验收：用 fake `SourceImageReader` 构造一个合法 `ImagegenPlanService.submit(...)` 调用。服务应通过 contracts 的 `PendingPlanPort` 写入 `pending_plan`，返回非空 planId；随后 `ImagegenConfirmationSupport.payloadHash(planId)` 能通过同一个 port 读回原始 payload 并重算 hash。DAG 路径 `SubmitImagePlanHandler` 仍能写入 `type="IMAGE_PLAN"` 的提案。两条路径共享一张表，但不共享彼此的校验逻辑。

确认链路验收：构造 `PendingProposalRepository.require(proposalId)` 对 `IMAGEGEN` 与 `IMAGE_PLAN` 两类记录均能返回 `PendingProposal`，并能提供真实 `payloadHash` 和 `expectedCount`。调用 `ConfirmationService.submit(...)` 时，仍先 challenge / token 校验，再创建 task，返回 `{ proposalId, taskId, status: "CONFIRMED" }`。前端看到的响应形状不变。

自动配置验收：`ConditionEvaluationReport` 中不应再出现 `ConversationAutoConfiguration#pendingPlanPort`，因为该 Bean 不再存在；应能看到 `DagAutoConfiguration#pendingPlanPort` 匹配。`ImagegenAutoConfiguration#imagegenPlanService` 不再因为缺 `PendingPlanPort` 失败。

文档验收：`docs/design-docs/base/contracts.md` 明确 `proposal` 子包只含纯契约；`module/dag.md` 明确 dag 提供 pending plan 持久化 port；`module/imagegen.md` 不再说 port 由 conversation 实现；`module/conversation.md` 不再要求 conversation 实现或写 pending plan。`docs/design-docs/frontend/api.md` 若未修改，应在本计划或实施记录中说明前端 API 未变。

## Idempotence and Recovery

本重构不可做半套。若只新增 contracts 新接口但保留 imagegen 旧接口，项目会同时存在两个 `PendingPlanPort`，后续导入错误会很难排查。执行时必须把旧文件删除，并让编译器暴露所有未迁移 import。

如果 Maven 编译在删除旧接口后失败，按报错逐个改 import，不要新增旧包 bridge。若测试 fake 实现失败，直接让 fake 实现 `com.pixflow.contracts.proposal.PendingPlanPort`。若自动配置失败，优先检查 dag 的 `AutoConfiguration.imports`、`@AutoConfiguration(after/before)` 和核心 Bean 是否被条件静默跳过，不要把 Bean 放回 conversation 兜底。

数据库不需要新增表。`pending_plan` 表已经存在，且 `type` 字段可区分 `IMAGE_PLAN` 与 `IMAGEGEN`。如果实施者决定给 `pending_plan` 增加 `expected_count`、`package_id` 或 `plan_type` 更清晰字段，必须写向后兼容 migration，并在确认边界继续以服务端重算事实为准，不允许只信旧列。

本计划不保留迁移式安全旧代码。回滚方式是通过 git revert 本次重构提交整体回滚，而不是在代码内长期保留两套接口。

## Artifacts and Notes

当前问题证据：

    pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/port/PendingPlanPort.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/proposal/PendingPlanPortAdapter.java
    pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/PendingPlanMapper.java
    pixflow-module-dag/src/main/resources/db/migration/V1__create_pending_plan.sql

当前启动错误链路：

    ToolsAutoConfiguration#toolRegistry
      -> submitImagegenPlanDescriptor
      -> imagegenPlanToolHandler
      -> imagegenPlanService
      -> missing com.pixflow.module.imagegen.port.PendingPlanPort

目标链路：

    contracts.proposal.PendingPlanPort
      <- dag.propose.PendingPlanPortAdapter 作为唯一生产实现
      <- imagegen.proposal.ImagegenPlanService 注入并写提案
      <- imagegen.confirm.ImagegenConfirmationSupport 注入并读提案
      <- conversation.proposal.PendingProposalRepository 通过 dag mapper/service 只读确认事实

适合放入哪个已有共享模块的结论：

    纯共享契约：pixflow-contracts/com.pixflow.contracts.proposal
    具体持久化实现：pixflow-module-dag/com.pixflow.module.dag.propose

不选择其他模块的理由：

    pixflow-common: 适合错误、分页、响应、脱敏等横切能力，不是解环 SPI 的仓库。
    pixflow-contracts: 适合 SPI/record，但不能放 Spring/MyBatis/DB 实现。
    pixflow-state: 运行态读模型与恢复协调，不拥有业务表。
    pixflow-session: message transcript 唯一写者，不应拥有 pending_plan。
    pixflow-conversation: 确认 REST 编排方，不应替 imagegen 实现提案写入 port。

## Interfaces and Dependencies

在 `pixflow-contracts/src/main/java/com/pixflow/contracts/proposal/PendingPlanPort.java` 定义：

    package com.pixflow.contracts.proposal;

    import java.util.Optional;

    public interface PendingPlanPort {
        String enqueue(PendingPlanProposal proposal);
        Optional<PendingPlanProposal> find(String planId);
    }

在 `pixflow-contracts/src/main/java/com/pixflow/contracts/proposal/PendingPlanProposal.java` 定义：

    package com.pixflow.contracts.proposal;

    import java.time.Instant;

    public record PendingPlanProposal(
            String planType,
            String payloadJson,
            String conversationId,
            String packageId,
            String toolCallId,
            Instant createdAt
    ) {
        public PendingPlanProposal {
            if (planType == null || planType.isBlank()) {
                throw new IllegalArgumentException("planType is required");
            }
            if (payloadJson == null || payloadJson.isBlank()) {
                throw new IllegalArgumentException("payloadJson is required");
            }
            if (conversationId == null || conversationId.isBlank()) {
                throw new IllegalArgumentException("conversationId is required");
            }
            if (toolCallId == null || toolCallId.isBlank()) {
                throw new IllegalArgumentException("toolCallId is required");
            }
            if (createdAt == null) {
                throw new IllegalArgumentException("createdAt is required");
            }
        }
    }

`pixflow-contracts` 仍然必须保持零内部依赖。compact constructor 的非空校验是值对象自洽，不是业务行为，可以接受。

在 `pixflow-module-dag/src/main/java/com/pixflow/module/dag/propose/PendingPlanPortAdapter.java` 定义生产实现：

    package com.pixflow.module.dag.propose;

    import com.pixflow.contracts.proposal.PendingPlanPort;
    import com.pixflow.contracts.proposal.PendingPlanProposal;

    public final class PendingPlanPortAdapter implements PendingPlanPort {
        ...
    }

该 adapter 可以复用现有 `PendingPlanMapper.findByToolCallId(...)` 保证幂等。唯一性当前是 `UNIQUE KEY uk_tool_call (tool_call_id)`，若未来不同 conversation 中 toolCallId 可能碰撞，应另起 migration 改为 `(conversation_id, tool_call_id)`，并同步测试；本重构不在同一阶段扩大该数据库行为。

`pixflow-module-imagegen` 依赖 `pixflow-contracts`。`ImagegenPlanService` 的构造参数改为：

    public ImagegenPlanService(
            ImagegenPlanValidator validator,
            com.pixflow.contracts.proposal.PendingPlanPort pendingPlanPort,
            ImagegenPayloadHasher payloadHasher,
            ObjectMapper objectMapper,
            Clock clock,
            ImagegenMetrics metrics)

`pixflow-conversation` 不再依赖 contracts proposal port 来写提案。它可以继续依赖 dag 的 mapper/service 读取并确认提案，因为确认编排是它的职责。

## Frontend API Impact

本重构目标是不改变任何浏览器前端接口。以下接口必须保持不变：

    POST /api/conversations/{conversationId}/confirm/{proposalId}/challenge
    POST /api/conversations/{conversationId}/confirm/{proposalId}/submit
    ConfirmationChallengeResponse: { needChallenge, challenge, token }
    ConfirmationSubmitResponse: { proposalId, taskId, status: "CONFIRMED" }
    SSE proposal / task progress / taskId 挂载行为

`proposalId` 仍然来自 `pending_plan.id` 的字符串形式，前端不需要知道 `PendingPlanPort` 位于哪个 Java 包，也不需要知道提案由 dag adapter 写入。如果实施过程中只是移动 Java SPI 和 Bean 所有权，不需要更新 `docs/design-docs/frontend/api.md`。如果为了本重构改变了 `proposalId` 格式、确认端点 body、返回字段、错误码或 SSE 事件 payload，则必须同步更新 `docs/design-docs/frontend/api.md`，并补 `pixflow-web/src/api/confirm.ts` 与 `useAgentTurn` 的测试。

## Revision Notes

2026-07-07 / Codex: 新建本文档。原因是启动报错暴露了 `PendingPlanPort` 所有权拆散的问题，用户要求检查适合放入哪个已有共享模块，并按 `PLANS.md` 撰写中文计划。本文结论是：纯共享契约进入 `pixflow-contracts` 的新 `proposal` 子包，持久化实现收敛到已拥有 `pending_plan` 的 `pixflow-module-dag`，删除 imagegen 旧 port 和 conversation adapter，不保留迁移式兼容旧代码。本计划同时记录前端 API 不应变化；若实施中改变浏览器可见契约，必须同步更新 `docs/design-docs/frontend/api.md`。

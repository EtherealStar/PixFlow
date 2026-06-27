# 实现 permission 确认令牌与硬安全边界

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一层独立的 permission 安全边界。Agent 可以生成建议、编译 DAG、查看数据和调用只读子 Agent，但不能绕过用户确认直接执行 `submit_dag` 或 `run_imagegen_subagent`。用户点击确认后，服务端签发一次性确认令牌；真正执行前，permission 会重新比对动作、会话、载荷哈希、工作单元数量和确认级别。这个行为可以通过单元测试和后续工具管线集成测试观察：无令牌时返回 `CONFIRM_REQUIRED`，有效令牌只允许成功一次，伪造、过期、载荷漂移或大批量级别不匹配都会被硬拒绝。

本计划只描述 permission 模块的实现，不实现登录、RBAC、多租户，也不让 permission 解析 DAG、读取素材包或调用 imagegen。那些业务事实由上层模块计算后，以最小权限视图交给 permission 比对。

## Progress

- [x] (2026-06-27 15:00+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/design.md`、`docs/design-docs/module/common.md`、`docs/design-docs/module/permission.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md` 以及相关 reference 文档，确认 permission 是 Wave 0 地基。
- [x] (2026-06-27 15:00+08:00) 修正 `docs/design-docs/module/permission.md` 中的接口依赖方向：permission 不直接引用 `ToolDescriptor` 或 `ToolCallClassification`，改为定义 `PermissionSubject`。
- [x] (2026-06-27 15:00+08:00) 新增本中文 ExecPlan，明确实现架构、机制、接口、验证方式和快速定位关键词。
- [ ] 实现 `common` 模块中 permission 需要依赖的错误契约，包括 `ErrorCode`、`ErrorCategory.PERMISSION`、`PixFlowException` 和脱敏工具。
- [x] (2026-06-27 14:17+08:00) 实现 permission 的纯模型、deny-first 策略、确认令牌服务和内存令牌存储。
- [x] (2026-06-27 14:17+08:00) 实现 permission 单元测试，覆盖三态决策、单次消费、载荷绑定、大批量确认和子 Agent 硬约束。
- [ ] 在 Wave 1 的 `infra/cache` 中实现 Redis 版 `ConfirmationTokenStore`，用原子读取并删除保证一次性消费。
- [ ] 在 Wave 3/4 的 `harness/tools`、`harness/loop`、`module/conversation`、`module/task`、`module/imagegen` 中接入 permission。

## Surprises & Discoveries

- Observation: `docs/design-docs/module/permission.md` 原先声明 permission 不依赖上层模块，但 `PermissionPolicy` 示例接口直接引用了 `ToolDescriptor` 和 `ToolCallClassification`。
  Evidence: 文档中同时出现“permission 不依赖任何上层模块”和 `evaluate(ToolDescriptor descriptor, ToolCallClassification classification, PermissionContext context)`。这会让 Wave 0 反向依赖 Wave 3 的 `harness/tools`，因此已改为 `PermissionSubject`。

- Observation: `.kiro/specs/pixflow/design.md` 是 MVP 阶段设计，不能作为本轮 permission 实现的主依据。
  Evidence: `docs/design-docs/design.md` 明确当前阶段是完整重写，不以既有 MVP 实现为约束；`permission.md` 也写明 MVP 无鉴权，本模块从新架构需求重新推导。

## Decision Log

- Decision: permission 定义自己的 `PermissionSubject`，由 `harness/tools` 后续适配生成，而不是在 permission 接口中引用 tools 内部类型。
  Rationale: `module-dependency-dag-plan.md` 要求 permission 位于 Wave 0，只依赖 common；直接引用 tools 类型会破坏 `common → permission → tools` 的依赖方向。
  Date/Author: 2026-06-27 / Codex

- Decision: permission 不计算 `payloadHash` 或 `actualCount`，只比对上层提供的真实执行事实。
  Rationale: 计算 DAG 哈希和工作单元数量需要 dag/file/task/imagegen 业务知识。如果 permission 自己计算，会变成业务模块并导致依赖倒挂。上层在确认和提交边界重新计算，permission 负责硬比对，安全性和边界都更清楚。
  Date/Author: 2026-06-27 / Codex

- Decision: permission 的默认实现采用 `DefaultPermissionPolicy` + `ConfirmationTokenService` 分层，策略层返回 `PermissionDecision`，令牌层抛出归一化 `PixFlowException`。
  Rationale: 这样可以把「正常需要确认」和「真实违规拒绝」分开处理，策略层适合给 loop/tool 管线消费，令牌层保留错误码语义，便于后续统一接入 `common` 的出口渲染。
  Date/Author: 2026-06-27 / Codex

- Decision: `submit_dag` 无令牌时返回 `CONFIRM_REQUIRED`，而不是抛权限错误。
  Rationale: 无令牌通常是正常 HITL 流程，表示系统需要暂停等待用户确认；只有伪造、过期、漂移、数量不符、只读子 Agent 越权等情况才是 `DENY`。
  Date/Author: 2026-06-27 / Codex

## Outcomes & Retrospective

当前完成的是设计修正和实现计划，不包含 Java 代码。最重要的收获是把 permission 的 Wave 0 依赖边界钉死：它只依赖 common 和自己的 SPI，不依赖 tools、dag、task、conversation、imagegen 或 infra。后续实施时，任何需要业务知识的计算都必须留在上层边界，permission 只接受最小事实并给出三态决策。

## Context and Orientation

本仓库当前处于 PixFlow 完整重写阶段。`docs/design-docs/design.md` 是总设计文档，说明 PixFlow 是一个电商运营 Agent：用户通过对话给出图片处理目标，Agent 召回记忆和电商数据，生成建议，用户确认后才执行批量图片处理或生图。`docs/design-docs/exec-plans/module-dependency-dag-plan.md` 是模块实施顺序，规定 Wave 0 是 `common` 和 `permission`，后续才是 infra、harness、业务 module 和 agent。

`permission` 是硬安全边界。硬安全边界的意思是：是否允许执行副作用动作由 Java 代码强制判断，不靠 prompt 文本提醒模型“不要执行”。副作用动作是会改变系统状态、产生任务、写入结果或调用生图服务的动作。本阶段重点保护两个 Agent 级动作：`submit_dag` 和 `run_imagegen_subagent`。

确认令牌是一个服务端生成的一次性随机句柄。用户在 UI 上确认执行后，REST 端点创建令牌并把令牌 id 放入服务端可信上下文。LLM 看不到令牌，工具参数里也没有 token 字段。执行前，permission 从 `PermissionContext.pendingToken` 读取令牌 id，并到 `ConfirmationTokenStore` 原子消费。原子消费的意思是读取和删除必须作为一个不可拆分的操作完成，保证同一令牌并发使用时只有一次成功。

`common` 是 permission 的唯一直接下游依赖。`docs/design-docs/module/common.md` 规定错误统一由 `PixFlowException` 承载，错误码实现 `ErrorCode`，权限类错误归 `ErrorCategory.PERMISSION`，默认不可重试、不可降级。

`harness/tools` 是后续接入方，不是 permission 的依赖。工具执行管线后续会做 schema 校验、工具分类、guard、permission、hook、handler。由于 tools 还没有在 Wave 0 实现，permission 必须自己定义最小输入模型 `PermissionSubject`，让 tools 将来的 `ToolDescriptor` 和工具分类结果适配进去。

## 关联设计文档快速定位关键词

在 `docs/design-docs/design.md` 中搜索 `安全边界是硬约束`，可以定位总设计原则，说明为什么不能靠 prompt 约束 Agent。搜索 `HITL 确认`，可以定位用户确认流程。搜索 `Tool Registry`，可以理解 Agent 级动作和 DAG 级像素工具为什么必须分离。搜索 `submit_dag(确认令牌)`，可以定位异步执行时序。

在 `docs/design-docs/module/permission.md` 中搜索 `PermissionSubject`，可以定位 permission 与 tools 的解耦接口。搜索 `确认令牌`，可以定位核心机制。搜索 `verifyAndConsume`，可以定位令牌校验顺序。搜索 `deny-first`，可以定位权限评估短路顺序。搜索 `子 Agent 硬约束`，可以定位 vision/imagegen 等子 Agent 的硬限制。搜索 `令牌与 LLM 隔离`，可以定位 token 不能进 prompt、transcript、tool schema 或 tool input 的边界。

在 `docs/design-docs/module/common.md` 中搜索 `PERMISSION`，可以定位权限错误的分类、恢复策略和渲染原则。搜索 `ErrorCode`，可以定位模块错误码如何接入 common。搜索 `Sanitizer`，可以定位日志和 trace 中令牌、密钥、路径等敏感信息的脱敏规则。

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索 `Wave 0`，可以定位 permission 的实施波次。搜索 `permission 前置到 Wave 0`，可以定位为什么 permission 必须先于 tools/loop/task 实现。

在 `docs/references/permission-architecture.md` 中搜索 `deny-first 顺序`，可以定位参考实现的短路思想。注意该参考来自 OneCode，业务内核是文件系统/命令授权，只能借鉴骨架，不能照搬规则字符串、目录授权或 session 目录授权。

## Plan of Work

第一阶段先实现 common 依赖。确认 `src/main/java` 下的根包为 `com.pixflow` 后，在 `common` 模块建立错误契约：`ErrorCode`、`ErrorCategory`、`RecoveryHint`、`PixFlowException`、`BusinessException` 和 `Sanitizer`。permission 只使用其中的 `ErrorCode`、`ErrorCategory.PERMISSION`、`RecoveryHint.TERMINATE` 和脱敏能力，不自己拼 HTTP 错误体。

第二阶段实现 permission 纯模型。创建 `com.pixflow.harness.permission` 包，放置 `PermissionAction`、`PermissionSource`、`PermissionDecision`、`PermissionContext`、`PermissionSubject`、`PermissionPolicy`、`PermissionErrorCode`。`PermissionSubject` 是上层工具调用被简化后的权限视图，字段包括 `toolName`、`readOnly`、可空的 `ConfirmationAction`、`conversationId`、`packageId`、`payloadHash`、`actualCount` 和 `metadata`。如果 `confirmationAction` 为空，表示普通动作；如果是 `SUBMIT_DAG` 或 `IMAGEGEN`，表示必须走确认令牌闸门。

第三阶段实现确认令牌服务。创建 `com.pixflow.harness.permission.token` 包，放置 `ConfirmationAction`、`ConfirmationLevel`、`ConfirmationToken`、`TokenClaims`、`ConfirmationTokenStore`、`ConfirmationTokenService`。`ConfirmationTokenStore` 是 SPI，也就是只定义能力不绑定实现的接口。Wave 0 中提供测试用的内存实现，生产 Redis 实现在 Wave 1 的 `infra/cache` 中补齐。`ConfirmationTokenService.issue` 只接收上层已经算好的 claims 并生成 tokenId；`verifyAndConsume` 只从 store 消费 token 并与 `PermissionSubject`、`PermissionContext` 比对。

第四阶段实现 deny-first 策略。`DefaultPermissionPolicy.evaluate` 先检查子 Agent 硬约束，再检查 `disabledTools` 和 `deniedTools`，再检查需要确认的动作。只读普通动作默认允许。无令牌的确认动作返回 `CONFIRM_REQUIRED`，并在 metadata 里写入 `requiredAction`、`requiredLevel`、`actualCount`、`confirmReason` 等供 loop 转成 UI 确认信息。令牌存在但校验失败时返回 `DENY` 或抛出带 `PermissionErrorCode` 的 `PixFlowException`，具体取舍要在实现时保持一致：策略层最好返回决策，工具管线边界再把硬拒绝渲染为错误。

第五阶段实现子 Agent 约束。创建 `com.pixflow.harness.permission.subagent.SubagentConstraint`。当 `PermissionContext.subagent` 非空且 `readOnly=true` 时，任何 `PermissionSubject.readOnly=false` 的调用直接拒绝。工具白名单和黑名单也在这个阶段检查，并且 `isToolVisible` 要让不可用工具不进入 LLM 可见 schema。

第六阶段补测试。测试应优先覆盖纯逻辑，不依赖 Spring 容器或 Redis。用内存 store 验证令牌只能消费一次。用构造好的 `PermissionSubject` 验证 `payloadHash` 漂移、`actualCount` 不一致、超阈值但只有 `NORMAL` 令牌、只读子 Agent 调 `submit_dag` 等路径。测试命名应直接表达行为，例如 `submitDagWithoutTokenReturnsConfirmRequired`、`tokenCannotBeConsumedTwice`、`payloadMismatchDeniesExecution`。

第七阶段在后续波次接入。`infra/cache` 提供 Redis store，必须用 Lua 或 Redis 事务保证读取并删除的原子性。`harness/tools` 负责把自己的 descriptor/classification 适配为 `PermissionSubject`。`harness/loop` 把 `CONFIRM_REQUIRED` 转成 SSE 业务结果。`module/conversation` 是唯一令牌签发入口。`module/task` 和 `module/imagegen` 在真正执行前重新计算业务事实，并通过 permission 校验。

## Concrete Steps

从仓库根目录运行以下命令确认当前状态：

    cd D:\study\PixFlow
    git status --short

预期当前只有文档改动或为空。如果已有用户改动，不要回滚，继续在自己的文件范围内工作。

实现前先读取这些文档：

    Get-Content AGENTS.md
    Get-Content PLANS.md
    Get-Content docs\design-docs\exec-plans\module-dependency-dag-plan.md
    Get-Content docs\design-docs\design.md
    Get-Content docs\design-docs\module\common.md
    Get-Content docs\design-docs\module\permission.md

实现阶段每完成一个里程碑后运行测试。若项目使用 Maven，优先运行：

    mvn test

如果仓库后续引入模块化 Maven 命令，应在本计划中补充准确命令。成功时应看到 Maven 的 BUILD SUCCESS，并且 permission 相关测试全部通过。

Wave 0 的最小验证应能通过单元测试证明以下行为：

    submit_dag without token -> PermissionAction.CONFIRM_REQUIRED
    submit_dag with valid NORMAL token under threshold -> PermissionAction.ALLOW and token consumed
    second use of same token -> PermissionAction.DENY or PixFlowException(CONFIRMATION_TOKEN_EXPIRED)
    submit_dag with different payloadHash -> PermissionAction.DENY or PixFlowException(CONFIRMATION_PAYLOAD_MISMATCH)
    submit_dag actualCount above bulk threshold with NORMAL token -> PermissionAction.DENY or PixFlowException(BULK_CONFIRMATION_REQUIRED)
    read-only vision subagent calls submit_dag -> PermissionAction.DENY or PixFlowException(SUBAGENT_FORBIDDEN_ACTION)

## Validation and Acceptance

本计划的验收不是“类存在”或“代码编译”，而是可观察的权限行为。Wave 0 完成时，运行 permission 单元测试应能看到所有三态路径都被覆盖：正常只读动作允许，副作用动作无令牌要求确认，有效令牌允许且只能使用一次，非法令牌硬拒绝。

当 `harness/tools` 接入后，端到端验收应是：用户发送一条会生成 DAG 的指令，Agent 返回建议和 `needConfirm=true`，但没有创建任务；用户确认后，服务端签发令牌，同一载荷提交成功并创建任务；如果在确认后篡改 DAG 或素材数量发生变化，提交被拒绝并返回权限类错误。

当 `module/imagegen` 接入后，生图路径应具有同样行为：无令牌不执行，确认后执行一次，重复提交同一令牌失败。

## Idempotence and Recovery

本计划推荐的实现是可重复执行的。纯模型和策略类是 additive changes，重复运行测试不会改变外部状态。内存令牌 store 只用于测试，每个测试应创建新实例，避免跨测试污染。

Redis store 实现时，保存令牌使用固定 key 前缀和 TTL；消费使用原子读取并删除。若消费后业务执行失败，不应恢复同一令牌，因为令牌代表“准许发起一次执行”，不是任务成功保证。用户需要重试时，应走重新确认并签发新令牌。

如果实现中发现 `common` 尚未完整落地，先补齐 permission 需要的最小 common 契约，不要让 permission 自己临时定义一套错误模型。否则后续会出现错误处理双轨制。

## Artifacts and Notes

本计划创建时已同步修正 `docs/design-docs/module/permission.md`。关键修正是把 permission 的评估入口从依赖上层 tools 类型改为依赖本模块自己的最小输入视图：

    PermissionDecision evaluate(PermissionSubject subject, PermissionContext context)

这个修正的目标是保持 Wave 0 依赖纯净。后续如果有人想把 `ToolDescriptor` 重新放进 permission 接口，应先更新 `module-dependency-dag-plan.md` 并解释为什么允许 permission 依赖 Wave 3；默认不允许这样做。

2026-06-27 / Codex: 更新进度并记录第一轮实现结果。当前代码已落地 permission 纯模型、默认策略、令牌服务、内存 store 和单测，并通过 `mvn test` 验证。下一步是把 Redis store 和工具管线接缝补上。

## Interfaces and Dependencies

最终应存在以下接口和类型。路径按设计包名描述，实际 Java 文件位置应位于项目 Spring Boot 主工程的 `src/main/java/com/pixflow/...` 下。

在 `com.pixflow.harness.permission` 中定义 `PermissionPolicy`。它负责对一次工具调用做权限决策：

    public interface PermissionPolicy {
        PermissionDecision evaluate(PermissionSubject subject, PermissionContext context);
        boolean isToolVisible(String toolName, PermissionContext context);
    }

在 `com.pixflow.harness.permission` 中定义 `PermissionSubject`。它是权限评估输入，不允许引用 `harness/tools` 的 descriptor 或 classification 类型：

    public record PermissionSubject(
        String toolName,
        boolean readOnly,
        ConfirmationAction confirmationAction,
        String conversationId,
        String packageId,
        String payloadHash,
        int actualCount,
        Map<String, Object> metadata
    ) {}

在 `com.pixflow.harness.permission` 中定义 `PermissionContext`。它承载服务端可信上下文，尤其是 LLM 不可见的 pending token：

    public record PermissionContext(
        String conversationId,
        ConfirmationToken pendingToken,
        SubagentConstraint subagent,
        Set<String> deniedTools,
        Set<String> disabledTools
    ) {}

在 `com.pixflow.harness.permission.token` 中定义 `ConfirmationTokenStore`。它是 Redis 实现的 SPI：

    public interface ConfirmationTokenStore {
        void save(String tokenId, TokenClaims claims, Duration ttl);
        Optional<TokenClaims> consume(String tokenId);
    }

在 `com.pixflow.harness.permission.token` 中定义 `TokenClaims`。它绑定确认动作与实际载荷：

    public record TokenClaims(
        ConfirmationAction action,
        String conversationId,
        String packageId,
        String payloadHash,
        ConfirmationLevel level,
        int expectedCount,
        Instant issuedAt,
        Instant expiresAt,
        String nonce
    ) {}

permission 只直接依赖 `common`。它不能直接依赖 `infra/cache`、`harness/tools`、`harness/loop`、`module/conversation`、`module/task`、`module/dag` 或 `module/imagegen`。Redis store 由 `infra/cache` 后续实现并注入；tools、loop 和业务模块只能调用 permission，不能被 permission 调用。

## Change Notes

2026-06-27 / Codex: 创建本计划，并同步修正 permission 设计文档中的上层类型依赖问题。原因是原接口会破坏 Wave 0 依赖方向；计划现在把 `PermissionSubject` 作为明确的解耦契约。

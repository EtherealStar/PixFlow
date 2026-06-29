# 完整实现 harness/tools 工具注册表与执行管线

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵守仓库根目录的 `PLANS.md`。执行本计划的开发者不需要知道本次对话的上下文，只要从头阅读本文，就能理解为什么要实现 `harness/tools` 模块、要改哪些文件、要运行哪些命令、怎样确认行为可用。

## Purpose / Big Picture

完成本计划后，PixFlow 将拥有生产级 Agent 工具运行时：模型输出的工具调用不会直接散落到业务代码里，而是统一经过注册表、输入校验、调用分类、权限评估、生命周期 hook、handler 执行、结果预算、trace 记录这条管线。用户能看到的效果是：Agent 可以安全地 `search` / `read` 商品数据，调用只读子 Agent，提交图片处理或生图的待确认提案；但 Agent 永远不能绕过用户确认直接执行 DAG 或生图。

本计划不是 MVP。实现必须覆盖完整工具注册表、可见集合单一来源、JSON Schema 校验、input-aware 分类、Plan 模式、permission/hook/storage/eval 接缝、并发调度、大结果外置、结构化 tool error、Spring Boot 自动装配和一组能证明安全边界有效的测试。

## Progress

- [x] (2026-06-29 00:00+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、当前 active exec plans、`docs/design-docs/design.md`、`docs/design-docs/harness/tools.md` 和 `docs/design-docs/harness/hooks.md`，确认 tools 是 Wave 3 横切组合模块，依赖 `permission`、`hooks`、`infra/storage` 和 `common`，不依赖业务模块或 `harness/eval`。
- [x] (2026-06-29 00:00+08:00) 按用户要求先同步旧设计文档口径，把旧 `query_commerce_data`、`compile_dag`、`submit_dag`、`run_vision_subagent`、`run_imagegen_subagent` 工具流更新为 `search`、`read`、`agent`、`submit_image_plan`、`submit_imagegen_plan`、`plan`、`plan_exit`，并把真实执行移到确认 REST 边界。
- [x] (2026-06-29 00:00+08:00) 创建本执行计划，明确 tools 模块的完整架构思路、机制、文件改动顺序、验证命令和设计文档快速定位关键词。
- [x] (2026-06-29 02:00+08:00) 创建 `pixflow-tools` Maven 模块并加入根 `pom.xml` 与 `pixflow-app` 依赖。
- [x] (2026-06-29 02:00+08:00) 实现 tools core：descriptor、handler、registry、executor、schema exporter、result policy、trace sink、result storage 和 error factory。
- [x] (2026-06-29 02:00+08:00) 接入 permission、hooks、storage 和 Spring Boot auto-configuration；完成最小构建闭环验证。
- [~] (2026-06-29 02:00+08:00) Plan 模式视图与 `plan` / `plan_exit` 控制工具接缝已预留，但尚未补全专门单测与真实会话控制器适配。
- [~] (2026-06-29 02:00+08:00) 业务模块仍将通过 Spring bean 贡献真实 `search` / `read` / `agent` / `submit_*_plan` handler；当前实现以 core 骨架和默认实现为主，后续需要补 fake handler 及完整 executor 行为测试。

## Surprises & Discoveries

- Observation: `docs/design-docs/harness/tools.md` 已经把 tools 口径重构为 7 个新工具，但 `design.md`、`module/commerce.md`、`infra/permission.md` 等旧文档仍残留旧工具流。
  Evidence: 运行 `rg "query_commerce_data|compile_dag|submit_dag|run_vision_subagent|run_imagegen_subagent" docs` 能看到旧引用；本计划创建前已同步 active 设计文档和 active exec plans。

- Observation: `permission` 的既有设计以 `submit_dag` / `run_imagegen_subagent` 工具携确认令牌为主，而 tools 新设计要求工具层零令牌、真实执行在确认 REST 边界。
  Evidence: `docs/design-docs/harness/tools.md` 搜索 `工具集零真实副作用、零令牌`；`docs/design-docs/infra/permission.md` 已同步为确认 REST 边界校验真实执行。

- Observation: 当前 `PermissionSubject` 的 `packageId` / `payloadHash` 字段对无包上下文的 `search`、`read`、`plan` 不自然。
  Evidence: `docs/design-docs/harness/tools.md` 搜索 `packageId/payloadHash`，可以看到建议把这两个字段放宽为可空；本计划把该点作为实现前必须对齐的接口微调。

- Observation: `pixflow-tools` 第一版实现已可独立编译并通过 `mvn -pl pixflow-tools -am test`，但当前还没有把 fake handler 单测补进模块。
  Evidence: 本地执行 `mvn -pl pixflow-tools -am test` 成功；`pixflow-infra-storage` 的 Testcontainers 集成测试因当前机器 Docker 环境不可用而跳过，未影响 tools 模块构建结果。

## Decision Log

- Decision: 先实现 tools core 和 fake handler 测试，再接真实业务工具。
  Rationale: `harness/tools` 的核心价值是安全执行管线，而不是某个具体工具。先用 fake descriptor 覆盖 registry、校验、permission、hook、并发、结果预算和 trace，可以在不等待 dag/vision/imagegen 的情况下把横切边界打牢。
  Date/Author: 2026-06-29 / Codex

- Decision: Agent 工具层保持零确认令牌。`submit_image_plan` 和 `submit_imagegen_plan` 只生成待确认提案；真实 DAG 执行和生图执行由确认 REST 端点触发。
  Rationale: 这比“让 Agent 调带令牌的执行工具”更强，因为 Agent 根本没有真实副作用扳机。令牌永不进入 prompt、transcript 或 tool schema，也不会被模型复读。
  Date/Author: 2026-06-29 / Codex

- Decision: `CONFIRM_REQUIRED` 如果出现在 tools executor 的 Agent 工具执行路径中，按 fail-closed 处理为权限类 tool error。
  Rationale: 新工具集零令牌，正常工具执行不应需要确认。若 permission 配置或 subject 构造错误导致 `CONFIRM_REQUIRED` 泄漏到工具层，继续等待会污染主循环语义；fail-closed 更安全，也更容易被测试发现。
  Date/Author: 2026-06-29 / Codex

- Decision: Plan 模式状态不由 tools 持久化。tools 只读取 loop/session 提供的 `PlanModeView`，并用它控制可见集、prompt section 和执行期 deny。
  Rationale: Plan 模式是会话级运行时状态，归属 loop/session。tools 持久化该状态会让横切模块承担会话职责，并引入后续恢复和多节点一致性问题。
  Date/Author: 2026-06-29 / Codex

## Outcomes & Retrospective

当前已完成 `pixflow-tools` 模块骨架、核心 DTO/SPI、默认 registry/executor、Spring Boot 自动装配入口，以及一次完整 Maven 构建验证。工具模块已经从空计划推进到可编译的基础实现。

下一阶段应补齐三类内容：一是 fake handler 单测，把 schema 校验、permission deny、hook 改写/阻断、结果预算和 trace 记录钉死；二是 Plan 模式控制工具的会话级接线；三是等待业务模块把真实 `search` / `read` / `agent` / `submit_*_plan` handler 以 Spring bean 方式接入。

## Context and Orientation

仓库是 Maven 多模块 Spring Boot 项目，根目录是 `D:\study\PixFlow`。已有基础模块包括 `pixflow-common`、`pixflow-permission`、`pixflow-hooks`、`pixflow-infra-storage`、`pixflow-context`、`pixflow-eval`、`pixflow-module-commerce`、`pixflow-module-file`、`pixflow-module-memory` 等。当前尚未实现 `pixflow-tools` 模块。

`harness/tools` 是 Agent 级工具注册表与执行管线。这里的“Agent 级工具”是模型可以请求调用的粗粒度动作，例如 `search`、`read`、`agent`、`submit_image_plan`、`submit_imagegen_plan`、`plan`、`plan_exit`。它们和 DAG 内部像素工具完全不同。DAG 内部像素工具是 `remove_bg`、`resize`、`compose_group` 这类确定性图片处理节点，由 `module/dag` 执行引擎管理，绝不能进入本 Registry。

“提案工具”是指只创建待确认记录、不执行真实副作用的工具。`submit_image_plan` 只提交一条确定性图片处理 DAG 提案；`submit_imagegen_plan` 只提交一条源图加提示词的生图提案。用户确认后，前端调用确认 REST 端点，服务端在该边界校验确认令牌并触发真实执行。Agent 工具层没有确认令牌，也不消费确认令牌。

“可见集合单一来源”是指 LLM 可见 tool schema 和 system prompt 里的工具说明必须来自同一个 `visibleDescriptors(context)`。如果某个工具被 disabled、denied 或 Plan 模式隐藏，它必须同时从 schema 和 prompt 消失，不能出现 prompt 说有工具但 schema 不给、或 schema 给了但 prompt 没说明的割裂。

“input-aware 分类”是指只读性、并发安全、Plan 模式准入和 permission subject 不是只看工具名，而是要看本次调用输入。例如 `agent(type=vision)` 是只读，`submit_image_plan` 是非只读提案动作。分类器异常时必须 fail-closed。

实现时需要重点参考以下设计文档。为了快速定位，每个文档后面列出建议搜索关键词。

在 `docs/design-docs/harness/tools.md` 中搜索：

    工具集零真实副作用、零令牌
    Descriptor 是工具的唯一事实来源
    可见集合单一来源
    ToolDescriptor
    ToolHandler
    ToolInvocation
    ToolCallClassification
    执行管线
    并发调度
    HITL 在带外
    Plan 模式机制
    prompt/schema 分离
    PreToolUse 改写重校验
    结果预算
    ToolTraceSink

在 `docs/design-docs/harness/hooks.md` 中搜索：

    PRE_TOOL_USE
    HookResult
    updatedInput
    改写必须重新过闸
    permission DENY
    阻断归一化
    ToolUsePayload
    首批业务 hook 的归属

在 `docs/design-docs/infra/permission.md` 中搜索：

    PermissionSubject
    PermissionContext
    PermissionDecision
    isToolVisible
    CONFIRM_REQUIRED
    Agent 工具层零令牌
    确认 REST 边界
    Plan 模式
    子 Agent 硬约束

在 `docs/design-docs/infra/storage.md` 中搜索：

    tool-results
    ObjectStorage
    put
    ObjectRef
    MinIO
    路径约定

在 `docs/design-docs/base/common.md` 中搜索：

    ErrorCategory
    RecoveryHint
    ToolErrorRenderer
    PixFlowException
    Sanitizer
    traceId

在 `docs/design-docs/infra/ai.md` 中搜索：

    ToolSchema
    ToolCall.argumentsJson
    toolChoice
    submit_image_plan
    Completed.toolCalls

在 `docs/design-docs/module/commerce.md` 中搜索：

    search / read 工具接入契约
    CommerceService.query
    read include=data
    missingSkus
    stale

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索：

    Wave 3
    harness/tools
    search/read
    submit_image_plan
    确认 REST 边界

## Plan of Work

先创建 `pixflow-tools` Maven 模块。根 `pom.xml` 的 `<modules>` 和 dependencyManagement 中加入该模块。模块源码根包使用 `com.pixflow.harness.tools`，物理路径建议是 `pixflow-tools/src/main/java/com/pixflow/harness/tools/`。模块依赖 `pixflow-common`、`pixflow-permission`、`pixflow-hooks`、`pixflow-infra-storage`、Spring Boot autoconfigure、Spring Boot validation、networknt JSON Schema validator、Resilience4j、Micrometer 和测试依赖。`pixflow-app` 依赖 `pixflow-tools`，用于后续自动装配验证。

然后实现核心 DTO 和 SPI。新增 `ToolDescriptor`、`ToolHandler`、`ToolHandlerOutput`、`ToolInvocation`、`ToolCallClassification`、`ToolClassifier`、`ToolInputValidator`、`ToolResultPolicy`、`ToolExecutionResult`、`ToolRegistry`、`ToolExecutor`、`ToolVisibilityContext`、`ToolExecutionContext`、`ToolCall`。这些类型必须保持业务无关，不引用 `module/dag`、`module/commerce`、`module/vision`、`module/imagegen` 或 `harness/eval`。

接着实现 `DefaultToolRegistry`。它通过 Spring 注入 `List<ToolDescriptor>`，构造期校验 name 必须是 snake_case、不能重复、schema 必须是 object。`visibleDescriptors(context)` 是唯一可见集合入口，叠加 disabled/denied tools、`PermissionPolicy.isToolVisible`、Plan 模式 `readOnlyHint` 过滤和 runtime hidden tools。`toolSchemas(context)` 与 `toolPromptSections(context)` 都只能从 `visibleDescriptors(context)` 派生。

实现 schema 导出和输入校验。`ToolSchemaExporter` 把 `ToolDescriptor.inputSchema` 投影成 provider-neutral schema map，后续由 `infra/ai` 适配为 Spring AI 工具定义。执行器使用 networknt JSON Schema validator 校验模型给出的 arguments。JSON Schema 失败返回 `invalid_tool_input` tool error，不调 handler。工具级 `ToolInputValidator` 用于表达 schema 不方便表达的值规则。

实现 `RegistryToolExecutor` 的单调用管线。固定顺序是 lookup、schema 校验、工具级 validate、classifier 分类、构造 `PermissionSubject`、`PermissionPolicy.evaluate`、`PreToolUse` hook、handler、结果预算、`PostToolUse` 或 `ToolError` hook、`ToolTraceSink`。permission `DENY` 必须在 handler 前短路，且不触发 `PreToolUse`。`CONFIRM_REQUIRED` 在 tools 层按 fail-closed 转权限类 tool error。`PreToolUse` 返回 updatedInput 时必须重新执行 schema、validate、classify、permission；hook 阻断归一化为 `VALIDATION` / `SKIP`，不能冒充 `PERMISSION`。

实现并发调度。`execute(List<ToolCall>, ToolExecutionContext)` 保留 provider 原始顺序。扫描相邻工具调用，把 descriptor 存在、schema/validate 通过、classifier 不抛异常且 `concurrencySafe=true` 的相邻调用聚为候选 batch。整批仍串行 preflight；如果 preflight 后任一项不可并发，整批降级串行。handler 阶段使用有界线程池和 `CompletableFuture`，上限来自 `pixflow.tools.max-concurrency`，默认 8。无论 handler 何时完成，结果必须按 provider 原始顺序返回。

实现结果预算。默认 `ToolResultPolicy` 是 `maxResultSizeChars=50000`、`persistWhenExceeded=true`、`previewChars=4000`。结果超阈值时，`ToolResultStorage` 把完整内容写入 `infra/storage` 的 `tool-results/{id}.txt`，模型可见内容只包含外置引用和预览。metadata 必须包含 `result_truncated`、`original_size_chars`、`max_result_size_chars`、对象引用。测试中使用内存替身。

实现错误归一化。`ToolErrorFactory` 把未知工具、schema 失败、validator 失败、分类异常、permission deny、hook block、handler 异常、result storage 异常统一转为 `ToolExecutionResult(error=true)`。模型可见错误只包含 safe message、category、recovery、traceId，不暴露堆栈、内部异常原文或敏感路径。

实现 Plan 模式接缝。定义 `PlanModeView` 或同等接口，至少能表达当前会话是否在 Plan 模式。`plan` 和 `plan_exit` 的 handler 不在 tools 内部持久化状态，而是通过 `PlanModeController` SPI 调 loop/session 置位或清除。Plan 模式下 `visibleDescriptors` 隐藏 `readOnlyHint=false` 的工具和 `plan` 自身，保留 `plan_exit`；执行期 permission 仍必须基于 input-aware `readOnly=false` 硬 deny 带后果调用。

最后做 Spring Boot 自动装配。新增 `ToolsProperties`、`ToolsAutoConfiguration` 和 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。默认提供 no-op `ToolTraceSink`、默认 `ToolResultStorage`、默认 `ToolResultPolicy`、`DefaultToolRegistry`、`RegistryToolExecutor`。如果外部没有提供 `PlanModeView` 或 `PlanModeController`，Plan 工具可以返回结构化不可用错误，但 tools core 不应启动失败。

## Concrete Steps

所有命令默认在仓库根目录 `D:\study\PixFlow` 执行。开始任何改动前先查看工作树，避免覆盖用户或其他 agent 的未提交改动：

    git status --short

预期：如果有与 `pixflow-tools`、根 `pom.xml`、`pixflow-app` 或 `docs/design-docs` 相关的未提交改动，先阅读差异并与其共存；不要回滚用户改动。

第一步创建模块骨架。新增：

    pixflow-tools/pom.xml
    pixflow-tools/src/main/java/com/pixflow/harness/tools/
    pixflow-tools/src/test/java/com/pixflow/harness/tools/
    pixflow-tools/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

同时修改根 `pom.xml`，在 `<modules>` 中加入：

    <module>pixflow-tools</module>

如果根 `pom.xml` 的 dependencyManagement 管理内部模块，也加入：

    com.pixflow:pixflow-tools:${project.version}

然后运行：

    mvn -pl pixflow-tools -am test

预期：Maven 能识别模块并完成最小测试。

第二步实现核心 DTO、SPI 和配置绑定，运行：

    mvn -pl pixflow-tools -am test

预期：`ToolsPropertiesTest`、`ToolDescriptorValidationTest` 通过，重复工具名和非法工具名会在 registry 构造期失败。

第三步实现 registry、visible descriptors、schema exporter 和 prompt sections，运行：

    mvn -pl pixflow-tools -am test

预期：fake descriptors 下，disabled/denied/Plan hidden 工具同时从 schemas 和 prompt sections 消失；`plan_exit` 在 Plan 模式下仍可见。

第四步实现单调用 executor 管线，使用 fake permission、fake hook registry、fake handler 测试。运行：

    mvn -pl pixflow-tools -am test

预期：schema 失败、validator 失败、permission deny、hook block、hook rewrite、handler exception 全部返回结构化 `ToolExecutionResult`，主调用不抛出异常。

第五步实现结果预算和 storage-backed `ToolResultStorage`。运行：

    mvn -pl pixflow-tools -am test

预期：超过阈值的 fake handler 输出被外置到内存替身或 fake storage，模型可见内容只有预览和引用，metadata 正确。

第六步实现相邻并发分批。运行：

    mvn -pl pixflow-tools -am test

预期：相邻并发安全调用并发执行，非并发调用单独串行；结果按 provider 原始顺序返回；batch 中任一项 preflight 后变非并发时整批降级串行。

第七步实现 Plan 模式控制工具和 auto-configuration，接入 `pixflow-app` 依赖后运行：

    mvn -pl pixflow-app -am test

预期：Spring context 可启动，`ToolRegistry`、`ToolExecutor`、no-op `ToolTraceSink`、默认 `ToolResultStorage` 可注入，不产生循环依赖。

第八步运行相关模块聚合测试：

    mvn -pl pixflow-tools,pixflow-hooks,pixflow-permission,pixflow-infra-storage,pixflow-app -am test

预期：tools 新增测试全部通过，已有 hooks/permission/storage/app 测试不退化。若某些 storage Testcontainers 测试按既有条件跳过，记录跳过数量；tools 的核心单测不能依赖 Docker。

## Validation and Acceptance

验收必须证明行为，而不只是编译。

Registry 验收：构造 `search`、`read`、`submit_image_plan`、`plan_exit` 四个 fake descriptor。普通模式下 schemas 和 prompt sections 都包含这四个工具。Plan 模式下只包含 `search`、`read`、`plan_exit`，且 `submit_image_plan` 同时从 schema 和 prompt section 消失。

校验验收：调用 fake `read`，传入缺少 `sku_id` 的 arguments。executor 应返回 `ToolExecutionResult(error=true)`，category 是 `VALIDATION`，handler 调用次数为 0。再传入合法 `sku_id`，handler 执行一次并返回正常结果。

permission 验收：fake permission 对 `submit_image_plan` 返回 `DENY` 时，executor 不触发 `PreToolUse`，不调 handler，返回 `PERMISSION` tool error。fake permission 返回 `CONFIRM_REQUIRED` 时，executor 也不调 handler，并按 fail-closed 返回权限类 tool error。

hook 改写验收：fake PreToolUse hook 把 `read` 的 `include` 从非法值改成 `["data"]`，executor 必须重新跑 schema、validator、classifier 和 permission 后再调 handler。另一个测试把 hook 改写成被 permission deny 的输入，必须仍然 deny。

hook 阻断验收：fake PreToolUse hook 返回 `blockingReason`，executor 返回 `VALIDATION` / `SKIP` tool error，不调 handler，且错误 category 不能是 `PERMISSION`。

结果预算验收：fake handler 返回 60KB 文本，默认预算 50KB。executor 应调用 `ToolResultStorage`，返回内容包含预览和对象引用，metadata 记录原始大小和 `result_truncated=true`。模型可见内容不能包含完整 60KB。

并发验收：构造三个相邻只读 fake 工具，每个 handler sleep 200ms。并发上限为 3 时，总耗时应明显低于 600ms，并且返回结果顺序仍与输入顺序一致。再插入一个 `concurrencySafe=false` 工具，断言它被单独串行执行。

依赖验收：运行依赖检查或简单编译断言，`pixflow-tools` 不依赖 `pixflow-module-dag`、`pixflow-module-commerce`、`pixflow-module-vision`、`pixflow-module-imagegen`、`pixflow-eval`。业务模块后续通过 Spring bean 贡献 descriptor，而不是 tools core 反向引用业务类。

必须至少运行并通过：

    mvn -pl pixflow-tools -am test
    mvn -pl pixflow-app -am test

如果运行聚合测试：

    mvn -pl pixflow-tools,pixflow-hooks,pixflow-permission,pixflow-infra-storage,pixflow-app -am test

预期是 BUILD SUCCESS。若 Testcontainers 环境不可用，只有既有 infra 集成测试可以按条件跳过；tools core 单元测试必须全部通过。

## Idempotence and Recovery

本计划的实现应可重复运行测试。`DefaultToolRegistry` 构造是纯内存收集，重复启动 Spring context 不应注册重复工具。`ToolResultStorage` 对同一次 toolCallId 可生成唯一对象 key，重复执行同一 fake call 不应覆盖别的结果；如果需要幂等，可在 metadata 中记录 toolCallId 和 storage key。

执行器不能让 handler 异常逃出主循环。任何 handler 抛出的 `RuntimeException` 都必须被转换为 tool error。hook 抛异常由 hooks core 隔离并写入 metadata，executor 只消费最终 `HookResult`。storage 外置失败时，executor 不应把完整大结果塞回模型；应返回结构化 storage/tool error 或严格截断预览并标记外置失败。

如果 permission 接口在实现中需要微调 `PermissionSubject.packageId` / `payloadHash` 可空，必须同步更新 `docs/design-docs/infra/permission.md` 和相关测试。微调应保持向后兼容：确认 REST 边界仍能为真实 DAG/imagegen 执行提供非空 packageId、payloadHash 和 actualCount。

如果 app 装配失败，先确认是否存在循环依赖。tools core 不应依赖业务模块；业务模块也不应在 auto-configuration 中强制要求 tools 已存在，除非用条件装配。可用 `mvn -pl pixflow-tools -am test` 缩小问题，再用 `mvn -pl pixflow-app -am test` 验证整合。

## Artifacts and Notes

目标执行管线摘要：

    registry.get(name)
      -> JSON Schema validate(arguments)
      -> ToolInputValidator.validate(arguments)
      -> ToolClassifier.classify(arguments)
      -> PermissionPolicy.evaluate(subject, context)
      -> PreToolUse hook
      -> if hook rewrites input: repeat validate/classify/permission
      -> handler.handle(invocation)
      -> apply ToolResultPolicy
      -> PostToolUse or ToolError hook
      -> ToolTraceSink.record(...)
      -> ToolExecutionResult returned to loop

本期 Agent 工具清单：

    search
    read
    agent
    submit_image_plan
    submit_imagegen_plan
    plan
    plan_exit

工具和真实副作用的边界：

    submit_image_plan
      -> validate shallow DAG input
      -> module/dag handler deep-validates with DagValidator
      -> create pending proposal
      -> return proposal id
      -> no task created, no token consumed

    user confirms proposal in UI
      -> confirmation REST endpoint
      -> permission verifies token and payload hash
      -> task/imagegen execution starts outside tools

## Interfaces and Dependencies

在 `pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolDescriptor.java` 定义：

    package com.pixflow.harness.tools;

    import java.util.Map;

    public record ToolDescriptor(
        String name,
        String description,
        Map<String, Object> inputSchema,
        Map<String, Object> outputSchema,
        String prompt,
        boolean readOnlyHint,
        ToolHandler handler,
        ToolClassifier classifier,
        ToolInputValidator validator,
        ToolResultPolicy resultPolicy
    ) {}

在 `pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolHandler.java` 定义：

    package com.pixflow.harness.tools;

    public interface ToolHandler {
        ToolHandlerOutput handle(ToolInvocation invocation);
    }

在 `pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolInvocation.java` 定义包含 toolCallId、toolName、arguments、conversationId、turnNo、traceId、RuntimeScope 和 metadata。`RuntimeScope` 可以复用 hooks 中的同名概念，若不能直接依赖具体类型，则在 tools 内定义最小等价 record 并在适配层转换。

在 `pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolCallClassification.java` 定义：

    public record ToolCallClassification(
        boolean readOnly,
        boolean concurrencySafe,
        String permissionSubjectName,
        Map<String, Object> subjectMetadata,
        ToolResultPolicy resultPolicy
    ) {}

在 `pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolRegistry.java` 定义：

    public interface ToolRegistry {
        Optional<ToolDescriptor> get(String name);
        List<ToolDescriptor> visibleDescriptors(ToolVisibilityContext context);
        List<Map<String, Object>> toolSchemas(ToolVisibilityContext context);
        List<String> toolPromptSections(ToolVisibilityContext context);
    }

在 `pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolExecutor.java` 定义：

    public interface ToolExecutor {
        List<ToolExecutionResult> execute(List<ToolCall> calls, ToolExecutionContext context);
    }

在 `pixflow-tools/src/main/java/com/pixflow/harness/tools/result/ToolResultStorage.java` 定义：

    public interface ToolResultStorage {
        StoredToolResult store(String toolCallId, String toolName, String content);
    }

在 `pixflow-tools/src/main/java/com/pixflow/harness/tools/result/ToolTraceSink.java` 定义：

    public interface ToolTraceSink {
        void record(ToolTraceEvent event);
    }

`pixflow-tools` 必须使用现有底层接口，不得直接依赖业务模块：

    com.pixflow.common.error.PixFlowException
    com.pixflow.common.error.ErrorCategory
    com.pixflow.common.error.RecoveryHint
    com.pixflow.harness.permission.PermissionPolicy
    com.pixflow.harness.permission.PermissionSubject
    com.pixflow.harness.permission.PermissionContext
    com.pixflow.harness.hooks.HookRegistry
    com.pixflow.harness.hooks.HookEvent
    com.pixflow.harness.hooks.payload.ToolUsePayload
    com.pixflow.infra.storage.ObjectStorage

`pixflow-tools` 不得依赖：

    pixflow-module-dag
    pixflow-module-commerce
    pixflow-module-vision
    pixflow-module-imagegen
    pixflow-module-task
    pixflow-agent
    pixflow-eval

业务模块后续通过 Spring bean 暴露 `ToolDescriptor`，由 `DefaultToolRegistry` 自动收集。`ToolTraceSink` 默认 no-op；后续由 app 或 loop 接线层桥接到 eval trace。

## Change Note

2026-06-29 / Codex: 初次创建计划。本文在同步旧设计文档后，将 `harness/tools` 的生产级实现拆成 core 管线、Plan 模式、permission/hook/storage 接缝、并发调度、结果预算和测试验收，明确工具层零令牌、真实副作用移到确认 REST 边界。

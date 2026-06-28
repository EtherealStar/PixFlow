# 实现 harness/hooks 生命周期扩展点总线

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文只规划 `harness/hooks` core 的实现和接线验收，不实现具体业务 hook，不实现 `harness/tools`、`harness/context`、`harness/loop` 或 `module/task` 的完整业务逻辑。

## Purpose / Big Picture

完成本计划后，PixFlow 会拥有一个生产级的生命周期扩展点总线。后续工具执行、主循环、上下文压缩和任务调度在关键时机可以派发事件，内部扩展可以同步观察、软阻断、浅层改写工具输入，或通过 metadata 传递附加信息。用户能间接看到的效果是：系统可以在工具执行前用 hook 拦截 DAG 参数异常，把它作为可恢复的结构化 tool error 回填给模型；也可以在 assistant 回复完成后后台触发记忆抽取，而不阻塞当前对话。

本计划的可观察结果不是一个新的 HTTP 页面，而是一组可重复运行的测试。实现完成后，运行 `mvn -pl pixflow-hooks -am test` 应看到 hooks core 的排序、短路、异常隔离、metadata 合并和 PreToolUse 改写传播测试全部通过。后续 `harness/tools` 接入后，再用工具管线测试证明：permission 硬拒绝不会触发 hook，hook 改写后必须重新走 validate、classify、guard 和 permission。

## Progress

- [x] (2026-06-28 16:30+08:00) 已阅读 `AGENTS.md`、`PLANS.md`、active exec plans、`docs/design-docs/design.md`、`docs/design-docs/harness/hooks.md`、`docs/design-docs/infra/permission.md`、`docs/design-docs/base/common.md`、`docs/design-docs/harness/context.md`、`docs/design-docs/module/memory.md`，确认 hooks 属于 Wave 2 harness 基础，不按 MVP 裁剪。
- [x] (2026-06-28 16:40+08:00) 已确认当前仓库真实工程结构为 Maven 多模块，根包为 `com.pixflow`，已有模块包括 `pixflow-common`、`pixflow-contracts`、`pixflow-permission` 和多个 `pixflow-infra-*`。
- [x] (2026-06-28 16:45+08:00) 已更新 `docs/design-docs/harness/hooks.md`，澄清 `updatedInput` 浅层 patch 语义、不可变 `ToolUsePayload` 链内传播、metadata 冲突规则、`hookErrors` 保留键、`PRE_COMPACT` 的 `compact.summaryInstructions` metadata key、首批业务 hook 归属以及工具管线接线验收。
- [x] (2026-06-28 16:50+08:00) 已创建本 ExecPlan 初稿，按 `PLANS.md` 要求写清实现架构、机制、检索关键词、具体步骤和验收方式。
- [x] (2026-06-28 16:57+08:00) 已新增 `pixflow-hooks` Maven 模块，并把它加入根 `pom.xml` 的 `<modules>` 和 `<dependencyManagement>`。
- [x] (2026-06-28 16:57+08:00) 已实现 hooks core 的 Java API、payload、registry、配置和单元测试；关键逻辑包含顺序派发、短路、异常隔离、PreToolUse 浅层 patch 传播、metadata 冲突收敛和 Spring bean 自动装配。
- [x] (2026-06-28 16:57+08:00) 已运行 `mvn -pl pixflow-hooks -am test`，确认 hooks core 单元测试通过。
- [ ] 后续在 `harness/tools`、`harness/context`、`harness/loop`、`module/task` 实现时按本文接线验收补集成测试。

## Surprises & Discoveries

- Observation: 用户指定的 `.kiro/specs/pixflow/design.md` 在当前工作区不存在，且现有执行计划记录 `.kiro` 旧设计属于 MVP 阶段，不应作为本轮生产级设计依据。
  Evidence: `Get-Content .kiro\specs\pixflow\design.md` 返回路径不存在；`docs/design-docs/exec-plans/mq-module-plan.md` 记录当前完整重写架构应以 `docs/design-docs/design.md` 为准。

- Observation: 文档中的部分路径已经迁移，permission 的权威设计在 `docs/design-docs/infra/permission.md`，common 的权威设计在 `docs/design-docs/base/common.md`，不是早期 exec plan 中提到的 `docs/design-docs/module/permission.md` 或 `docs/design-docs/module/common.md`。
  Evidence: `rg --files docs\design-docs` 显示实际文件位于 `infra/` 和 `base/` 目录。

- Observation: 当前代码已经是 Maven 多模块，根包为 `com.pixflow`，permission 模块已落地为 `pixflow-permission`，因此 hooks 不应放进单体 `src/main/java`，而应新增独立模块 `pixflow-hooks`。
  Evidence: 根 `pom.xml` 的 `<modules>` 已列出 `pixflow-common`、`pixflow-contracts`、`pixflow-permission`、`pixflow-infra-*`；`pixflow-app/src/main/java/com/pixflow/PixFlowApplication.java` 声明 `package com.pixflow`。

## Decision Log

- Decision: `harness/hooks` core 新增为独立 Maven 模块 `pixflow-hooks`，包名使用 `com.pixflow.harness.hooks`。
  Rationale: 当前仓库已按多模块组织，permission 已独立为 `pixflow-permission`。hooks 在依赖 DAG 中位于 `permission → hooks → tools/loop`，单独模块能保持依赖边界清楚，也方便运行 `mvn -pl pixflow-hooks -am test` 做局部验证。
  Date/Author: 2026-06-28 / Codex

- Decision: `updatedInput` 采用浅层 patch 语义，不做整包替换、嵌套深合并、字段删除或类型转换。
  Rationale: hooks core 不理解具体工具 schema。浅层 patch 足以覆盖输入规范化场景，同时能避免 callback B 无意整包覆盖 callback A 的改写。深层语义应由工具自己的 validate/normalize 逻辑处理。
  Date/Author: 2026-06-28 / Codex

- Decision: PreToolUse 链内改写通过创建新的不可变 `ToolUsePayload` 传播给后续 callback。
  Rationale: 项目设计偏好 record 和防御性拷贝。不可变 payload 可以防止 callback 通过共享 Map 或 setter 污染后续状态，registry 内部用聚合器维护当前 payload 即可。
  Date/Author: 2026-06-28 / Codex

- Decision: `hookErrors` 是 dispatcher 保留 metadata key，callback 不能伪造。
  Rationale: `hookErrors` 代表 registry 捕获到的真实 callback 异常。如果普通 callback 能写这个 key，调用方无法区分系统隔离结果与业务 metadata，审计会被污染。
  Date/Author: 2026-06-28 / Codex

- Decision: `PRE_COMPACT` 的摘要指令注入使用 metadata key `compact.summaryInstructions`，不使用 `updatedInput`。
  Rationale: `updatedInput` 专属于工具输入改写；context 的摘要请求不是工具输入。分离这两个通道可以避免 hooks core 理解 compaction 业务语义。
  Date/Author: 2026-06-28 / Codex

- Decision: hooks core 不内置 DAG、memory 或 compaction 业务 hook。
  Rationale: hooks 是横切 SPI，总线不应反向依赖业务模块。业务 hook 应在更高层模块或接线层作为 Spring bean 注册。
  Date/Author: 2026-06-28 / Codex

## Outcomes & Retrospective

本计划当前完成了设计澄清和实现计划，不包含 Java 代码。最重要的结果是把 hooks core 的职责钉死为薄同步总线：它负责派发、短路、改写传播、metadata 合并和异常隔离，不负责权限、不负责业务、不负责 trace、不负责 tool error 渲染。后续实现时，任何把业务逻辑塞进 `pixflow-hooks` 的尝试都应先回到本计划和 `docs/design-docs/harness/hooks.md` 重新评估依赖方向。

2026-06-28 / Codex: 已完成 `pixflow-hooks` core 实现和测试验证。新增模块只依赖 `pixflow-common`、`pixflow-permission` 与最小 Spring 配置依赖，未接入业务 hook。验收命令 `mvn -pl pixflow-hooks -am test` 成功，hooks 模块 10 个测试通过，连同 common/permission 依赖测试共 32 个测试通过。

## Context and Orientation

PixFlow 当前处于完整重写阶段。根目录 `pom.xml` 是 Maven 父工程，已经包含 `pixflow-common`、`pixflow-contracts`、`pixflow-permission` 和多个 `pixflow-infra-*` 模块。`pixflow-common` 提供统一错误模型、脱敏和 tool error 渲染；`pixflow-permission` 提供确认令牌和硬安全边界；hooks 模块要依赖它们，但不能依赖未来的 `harness/tools`、`harness/loop`、`harness/context`、`module/task` 或任何业务模块。

这里定义几个术语，便于完全不了解本仓库的读者继续实施。

生命周期事件是系统运行到某个关键时刻时发出的内部事件，例如用户提交消息、工具执行前、工具执行后、assistant 消息完成、上下文压缩前、任务创建后。回调是一个 Spring bean，实现 `HookCallback` 接口，声明自己关心哪些事件，并在事件发生时返回 `HookResult`。软阻断是 hook 返回 `blockingReason`，表示本次工具或任务不应继续，但这不是权限硬拒绝；工具场景中软阻断会被调用方转成 `VALIDATION` 类 tool error，恢复建议是 `SKIP`，主循环继续。硬拒绝属于 permission 模块，分类是 `PERMISSION`，恢复建议是 `TERMINATE`。metadata 是 hook 给调用方或其他 hook 留下的结构化附加信息。`updatedInput` 是 PreToolUse 才使用的工具输入浅层 patch。

实现时优先阅读这些文档，并可用括号中的关键词快速定位设计依据。

在 `docs/design-docs/harness/hooks.md` 中搜索：`hooks 不是安全边界`、`改写必须重新过闸`、`薄总线`、`HookResult`、`ToolUsePayload`、`浅层 patch`、`metadata 累积`、`compact.summaryInstructions`、`首批业务 hook`、`permission 短路不触发 hook`。这些位置解释 hooks 的核心边界、结果语义、payload 形状和接线约束。

在 `docs/design-docs/infra/permission.md` 中搜索：`PermissionDecision`、`PermissionSubject`、`Hook allow 不能覆盖权限 deny`、`validate → classify → guard → permission → PreToolUse hook`、`DENY`、`CONFIRM_REQUIRED`。这些位置解释为什么 permission 必须先于 hook 执行，以及为什么被 permission 拒绝的调用不能触发 PreToolUse。

在 `docs/design-docs/base/common.md` 中搜索：`ErrorCategory`、`RecoveryHint`、`ToolErrorRenderer`、`VALIDATION`、`PERMISSION`、`Sanitizer`、`ErrorNormalizer`。这些位置解释 hook 阻断如何由调用方渲染为结构化 tool error，以及回调异常为什么要脱敏后进入 metadata。

在 `docs/design-docs/harness/context.md` 中搜索：`SummarizationPort`、`summaryInstructions`、`PreCompact`、`CompactFailed`、`确定性兜底`。这些位置解释 `PRE_COMPACT` 为什么通过 metadata 注入摘要指令。

在 `docs/design-docs/module/memory.md` 中搜索：`异步巩固`、`AssistantMessageCompleted`、`ingestAsync`、`ADD-only`。这些位置解释 memory hook 为什么必须异步、为什么不能阻塞当前回合。

在 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 中搜索：`Wave 2`、`hooks`、`permission --> hooks`、`hooks --> tools`、`tools --> loop`。这些位置解释 hooks 的实现顺序和依赖方向。

## Plan of Work

第一阶段建立 Maven 模块边界。新增目录 `pixflow-hooks/`，创建 `pixflow-hooks/pom.xml`，父工程是根 `pom.xml`。该模块依赖 `pixflow-common`、`pixflow-permission` 和 Spring Boot test。根 `pom.xml` 的 `<modules>` 增加 `pixflow-hooks`，`dependencyManagement` 增加 `com.pixflow:pixflow-hooks:${project.version}`。不要让 `pixflow-hooks` 依赖 `pixflow-app`、`pixflow-infra-*` 或尚未实现的业务模块。

第二阶段实现 hooks core API。新建 `pixflow-hooks/src/main/java/com/pixflow/harness/hooks/HookEvent.java`、`HookCallback.java`、`HookResult.java`、`HookRegistry.java`。`HookEvent` 包含 `USER_PROMPT_SUBMIT`、`PRE_TOOL_USE`、`POST_TOOL_USE`、`TOOL_ERROR`、`ASSISTANT_MESSAGE_COMPLETED`、`TURN_STOPPED`、`TASK_CREATED`、`TASK_COMPLETED`、`PRE_COMPACT`、`POST_COMPACT`、`COMPACT_FAILED`。`HookCallback` 暴露 `supportedEvents()`、`order()` 和 `handle(...)`。`HookResult` 必须在 compact constructor 中防御性拷贝 `updatedInput` 和 `metadata`，并提供 `noop()`、`block(...)`、`rewrite(...)`、`withMetadata(...)` 这些静态工厂。

第三阶段实现 payload。新建 `payload/HookPayload.java` sealed interface、`RuntimeScope.java`、`UserPromptSubmitPayload.java`、`ToolUsePayload.java`、`AssistantMessagePayload.java`、`TurnStoppedPayload.java`、`TaskLifecyclePayload.java`、`CompactionPayload.java`。所有 record 都要防御性拷贝 Map/List 字段。`ToolUsePayload` 依赖 `com.pixflow.harness.permission.PermissionDecision`，字段包括 conversationId、turnNo、traceId、runtime、phase、toolName、toolCallId、toolInput、permissionDecision、resultSummary。为了让 registry 用不可变方式传播 PreToolUse 改写，`ToolUsePayload` 应提供类似 `withToolInput(Map<String,Object> newInput)` 的方法，返回新 record。

第四阶段实现 registry 内部机制。新建 `DefaultHookRegistry`，构造期接收 `List<HookCallback>`，按 `supportedEvents()` 分桶，并按 `order()` 升序排序。实现时可以增加 `internal/DispatchAccumulator.java` 和 `internal/MetadataMerger.java`。`dispatch(event, payload)` 要同步执行同事件桶内的 callback。callback 返回 null 视为 noop。callback 抛出 `Throwable` 时，用 `Sanitizer.sanitizeMessage` 脱敏后记录到 metadata 保留键 `hookErrors`，继续执行后续 callback。若 `pixflow.hooks.fail-fast-on-callback-error=true`，本地调试模式下可以把异常重新抛出或返回阻断，但默认必须是隔离不中断。

第五阶段实现浅层改写传播。只有 `event == PRE_TOOL_USE` 且当前 payload 是 `ToolUsePayload` 时，registry 才消费 `HookResult.updatedInput`。消费方式是浅层 patch：复制当前 toolInput，逐个 put `updatedInput` 顶层键，生成新的 `ToolUsePayload` 给后续 callback。非 PreToolUse 事件即使 callback 返回 updatedInput，也只在最终 HookResult 中保留聚合信息或直接忽略控制流，不得改 payload。registry 最终返回的 HookResult 应包含聚合后的 blockingReason、最终 updatedInput patch 和 metadata。

第六阶段实现 metadata 合并。`MetadataMerger` 要固定冲突策略：新 key 直接写入；同 key 且值相等时保留单值；同 key 且旧值不是 List 时变为 List；同 key 且旧值已是 List 时追加。callback 返回 metadata 中的 `hookErrors` 必须忽略或迁移到 `callbackMetadata.hookErrors`，不能污染 dispatcher 保留键。`PRE_COMPACT` 没有特殊代码路径，只是约定调用方读取 `metadata["compact.summaryInstructions"]`。

第七阶段实现配置。新建 `config/HookProperties.java`，绑定 `pixflow.hooks.fail-fast-on-callback-error`，默认 false。新建 `config/HookAutoConfiguration.java`，在 Spring 容器中存在 `List<HookCallback>` 时装配 `HookRegistry`。如果项目现有自动配置模式使用普通 `@Configuration`，按已有 infra 模块风格实现；如果未来引入 Spring Boot auto-configuration imports，再同步调整。

第八阶段补齐单元测试。测试放在 `pixflow-hooks/src/test/java/com/pixflow/harness/hooks/`。优先写纯单元测试，不需要 Spring 容器。测试要覆盖排序和短路、异常隔离、fail-fast 调试配置、浅层 patch 传播、非 PreToolUse 忽略 updatedInput、metadata 冲突收敛、`hookErrors` 保留键保护、`compact.summaryInstructions` 多 hook 收敛、子 Agent self-gate 示例、payload 防御性拷贝。再写一个 Spring 装配测试，证明多个 `HookCallback` bean 会被收集并按 order 派发。

第九阶段在后续模块接线时补集成验收。`harness/tools` 接入后必须增加测试：permission `DENY` 和 `CONFIRM_REQUIRED` 不触发 `PRE_TOOL_USE`；PreToolUse 改写后重新 validate、classify、guard、permission；改写后的 permission DENY 仍短路。`harness/context` 接入后必须增加测试：`PRE_COMPACT` 的 `compact.summaryInstructions` 进入 `SummarizationRequest`；`COMPACT_FAILED` 在摘要失败或断路回退时发出。`module/task` 接入后必须增加测试：`TASK_CREATED` 阻断会回滚已创建任务，并归一化为 `BUSINESS_RULE`。

## Concrete Steps

从仓库根目录开始：

    cd D:\study\PixFlow
    git status --short

如果存在与 hooks 无关的用户改动，不要回滚。继续只编辑 `docs/design-docs/harness/hooks.md`、`docs/design-docs/exec-plans/hooks-module-plan.md` 和后续实现所需的 hooks 模块文件。

确认当前工程结构：

    cd D:\study\PixFlow
    rg --files -g "pom.xml" -g "*.java" | Select-Object -First 120

预期能看到根 `pom.xml`、`pixflow-common/pom.xml`、`pixflow-permission/pom.xml`、`pixflow-app/src/main/java/com/pixflow/PixFlowApplication.java`。如果根包不是 `com.pixflow`，以后者为准；当前已确认是 `com.pixflow`。

实现模块后运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-hooks -am test

成功时应看到 Maven `BUILD SUCCESS`，并且 hooks 相关测试全部通过。测试数量以后续实现为准，但至少应包含 registry、payload、metadata、configuration 四类测试。

后续 tools 接入时运行：

    cd D:\study\PixFlow
    mvn -pl pixflow-hooks,pixflow-permission -am test

如果当时已新增 `pixflow-tools` 模块，则改为：

    cd D:\study\PixFlow
    mvn -pl pixflow-tools -am test

该命令应证明 permission 硬拒绝路径不会派发 PreToolUse，hook 改写路径会重新授权。

## Validation and Acceptance

hooks core 的验收以可观察测试行为为准。

第一类验收是顺序与短路。构造三个 callback，order 分别为 -10、0、10。第一个写 metadata，第二个返回 `blockingReason`，第三个若执行就写标记。调用 `dispatch(PRE_TOOL_USE, payload)` 后，应观察到执行顺序为 -10 再 0，第三个没有执行，返回结果包含 blockingReason 和第一个 callback 的 metadata。

第二类验收是异常隔离。构造 callback A 抛异常，callback B 返回 metadata。默认配置下，调用 dispatch 不抛出异常，返回 metadata 中有 `hookErrors`，同时保留 callback B 的 metadata。`hookErrors` 的 message 应经过 `Sanitizer`，不能包含 token、AK/SK 或过长原文。

第三类验收是 PreToolUse 浅层 patch。构造初始 toolInput 为 `{width: 800, format: "png", nested: {a: 1}}`。callback A 返回 updatedInput `{format: "webp", quality: 80, nested: {b: 2}}`。callback B 读取 payload，应该看到 `{width: 800, format: "webp", quality: 80, nested: {b: 2}}`。这证明顶层覆盖和新增生效，也证明 nested 没有深合并成 `{a:1,b:2}`。

第四类验收是 metadata 冲突。callback A 写 `dag.validationWarnings = "missing expected_count"`，callback B 写同 key 为 `"unsafe resize"`。返回 metadata 中该 key 应是按执行顺序排列的列表。callback 试图写 `hookErrors` 时，最终系统级 `hookErrors` 不应被伪造。

第五类验收是 PreCompact 指令注入。两个 callback 都订阅 `PRE_COMPACT`，分别写 `compact.summaryInstructions`。返回 metadata 应按顺序保留两个 instruction，且 `updatedInput` 不影响 payload。

第六类验收是 Spring 装配。创建测试配置注册多个 `HookCallback` bean，注入 `HookRegistry`，调用 dispatch 后验证 bean 被收集并按 order 执行。

第七类验收属于后续接线。`harness/tools` 接入后，要构造 permission DENY 场景，断言 callback 计数为 0；构造 hook 改写后 permission DENY 场景，断言 handler 未执行，返回硬拒绝或确认语义由 tools 正确处理。这个测试不是 hooks core 单测，但必须作为 hooks 模块交付后的集成验收保留。

## Idempotence and Recovery

本计划推荐的实现是 additive changes。新增 `pixflow-hooks` 模块、向根 `pom.xml` 添加模块和依赖管理、添加测试都可以重复运行。若 Maven 测试失败，先用 `mvn -pl pixflow-hooks -am test -DskipTests` 判断是否是编译问题，再运行单个测试类定位行为问题。

如果新增模块过程中根 `pom.xml` 改坏，查看 `git diff pom.xml`，只保留 `<module>pixflow-hooks</module>` 和 dependencyManagement 中的 `pixflow-hooks` 条目。不要使用 `git reset --hard` 或回滚用户无关改动。

如果实现中发现 `pixflow-permission` 的 `PermissionDecision` API 与本文假设不一致，以当前 Java 源码为准，并更新本计划的 `Surprises & Discoveries` 和 `Decision Log`。当前源码中 `PermissionDecision` 已是不可变 record，metadata 会防御性拷贝，适合作为 `ToolUsePayload` 的只读字段。

如果 `Sanitizer` 或 `ToolErrorRenderer` API 后续调整，hooks core 不应直接渲染 tool error。只需要继续用 `Sanitizer` 处理回调异常摘要；tool error 仍由 `harness/tools` 调用 common 的渲染器完成。

## Artifacts and Notes

本计划创建前已同步更新 `docs/design-docs/harness/hooks.md`，关键澄清包括：

    - `updatedInput` 是 PreToolUse 专用的浅层 patch，不是整包替换。
    - PreToolUse 链内改写通过新的不可变 `ToolUsePayload` 传播。
    - metadata 同 key 冲突收敛为列表，`hookErrors` 是 dispatcher 保留键。
    - `PRE_COMPACT` 通过 `compact.summaryInstructions` metadata 注入摘要指令。
    - hooks core 不内置 DAG、memory、compaction 业务 hook。
    - permission `DENY` / `CONFIRM_REQUIRED` 不触发 PreToolUse，hook 改写后必须重新过权限。

当前真实工程证据：

    根 pom.xml modules:
        pixflow-contracts
        pixflow-common
        pixflow-permission
        pixflow-infra-storage
        pixflow-infra-cache
        pixflow-infra-mq
        pixflow-infra-vector
        pixflow-infra-ai
        pixflow-infra-image
        pixflow-infra-thirdparty
        pixflow-app

    应新增:
        pixflow-hooks

## Interfaces and Dependencies

最终应存在以下 Maven 模块和 Java 类型。

在根 `pom.xml` 中，新增模块和 dependencyManagement 条目：

    <module>pixflow-hooks</module>

    <dependency>
        <groupId>com.pixflow</groupId>
        <artifactId>pixflow-hooks</artifactId>
        <version>${project.version}</version>
    </dependency>

在 `pixflow-hooks/pom.xml` 中，依赖：

    com.pixflow:pixflow-common
    com.pixflow:pixflow-permission
    org.springframework.boot:spring-boot-starter-test (test scope)

如自动配置需要 Spring context 类型，允许添加最小 Spring Boot starter 或使用已有父工程传递的 Spring Boot 配置能力；不要引入 web、db、mq、redis、storage 或业务模块依赖。

在 `pixflow-hooks/src/main/java/com/pixflow/harness/hooks/HookEvent.java` 中定义：

    public enum HookEvent {
        USER_PROMPT_SUBMIT,
        PRE_TOOL_USE,
        POST_TOOL_USE,
        TOOL_ERROR,
        ASSISTANT_MESSAGE_COMPLETED,
        TURN_STOPPED,
        TASK_CREATED,
        TASK_COMPLETED,
        PRE_COMPACT,
        POST_COMPACT,
        COMPACT_FAILED
    }

在 `HookResult.java` 中定义不可变 record：

    public record HookResult(
            String blockingReason,
            Map<String, Object> updatedInput,
            Map<String, Object> metadata) {

        public static HookResult noop();
        public static HookResult block(String reason);
        public static HookResult rewrite(Map<String, Object> updatedInput);
        public static HookResult withMetadata(Map<String, Object> metadata);
        public boolean blocked();
        public boolean inputRewritten();
    }

在 `HookCallback.java` 中定义：

    public interface HookCallback {
        Set<HookEvent> supportedEvents();
        default int order() { return 0; }
        HookResult handle(HookEvent event, HookPayload payload);
    }

在 `HookRegistry.java` 中定义：

    public interface HookRegistry {
        HookResult dispatch(HookEvent event, HookPayload payload);
    }

在 `payload/HookPayload.java` 中定义 sealed interface：

    public sealed interface HookPayload
            permits UserPromptSubmitPayload, ToolUsePayload, AssistantMessagePayload,
                    TurnStoppedPayload, TaskLifecyclePayload, CompactionPayload {

        String conversationId();
        Integer turnNo();
        String traceId();
        RuntimeScope runtime();
    }

在 `payload/RuntimeScope.java` 中定义：

    public record RuntimeScope(boolean subagent, String subagentType) {
        public static RuntimeScope main();
        public static RuntimeScope of(String type);
    }

在 `payload/ToolUsePayload.java` 中定义：

    public record ToolUsePayload(
            String conversationId,
            Integer turnNo,
            String traceId,
            RuntimeScope runtime,
            HookEvent phase,
            String toolName,
            String toolCallId,
            Map<String, Object> toolInput,
            PermissionDecision permissionDecision,
            Map<String, Object> resultSummary) implements HookPayload {

        public ToolUsePayload withToolInput(Map<String, Object> newInput);
    }

`PermissionDecision` 来自 `pixflow-permission/src/main/java/com/pixflow/harness/permission/PermissionDecision.java`。hooks 只能读它，不能调用 permission policy，也不能翻转 permission 的 action。

在 `internal/MetadataMerger.java` 中实现：

    final class MetadataMerger {
        static final String HOOK_ERRORS_KEY = "hookErrors";
        Map<String, Object> merge(Map<String, Object> current, Map<String, Object> incoming);
        Map<String, Object> appendHookError(Map<String, Object> current, HookError error);
    }

在 `error/HookError.java` 中定义：

    public record HookError(
            String callback,
            String category,
            String safeMessage) {}

`category` 可以用异常简单类名或归一化分类；实现时如果能通过 `ErrorNormalizer` 得到 `PixFlowException`，就使用其 category，否则使用 `INTERNAL`。无论哪种，都必须经 `Sanitizer.sanitizeMessage` 处理 safeMessage。

在 `config/HookProperties.java` 中定义：

    @ConfigurationProperties(prefix = "pixflow.hooks")
    public class HookProperties {
        private boolean failFastOnCallbackError = false;
    }

在 `config/HookAutoConfiguration.java` 中装配：

    @Configuration
    @EnableConfigurationProperties(HookProperties.class)
    public class HookAutoConfiguration {
        @Bean
        public HookRegistry hookRegistry(List<HookCallback> callbacks, HookProperties properties) {
            return new DefaultHookRegistry(callbacks, properties);
        }
    }

## Change Notes

2026-06-28 / Codex: 创建本计划，并同步更新 `docs/design-docs/harness/hooks.md` 的设计澄清。原因是原 hooks 设计已经确定总体方向，但 `updatedInput`、metadata 冲突、PreCompact 指令注入、业务 hook 归属和 tools 接线验收尚未具体到可实现程度；本计划把这些点固定为后续实现必须遵守的生产级约束。

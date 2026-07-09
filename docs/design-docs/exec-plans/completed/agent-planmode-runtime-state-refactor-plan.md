# 重构 Plan 模式运行态传递，消除 RuntimeState 单例注入错误

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文面向完全不了解当前会话的执行者：只要有当前工作树和本文件，就应能完成重构、验证结果，并知道为什么这样做。

## Purpose / Big Picture

当前 `pixflow-app` 启动失败于 Spring Bean 装配：`AgentPlanExitToolHandler` 是由 Spring 创建的单例组件，但它的构造函数第二个参数需要 `com.pixflow.harness.loop.RuntimeState`。`RuntimeState` 是每个会话回合现场创建的可变运行态，不是 Spring 容器中的全局 Bean，因此启动期找不到候选 Bean。这个错误会阻止后端服务启动。

完成本计划后，`plan` 和 `plan_exit` 工具仍能修改当前回合的 Plan 模式状态，但它们不再通过构造函数注入 `RuntimeState`。工具执行链路会显式携带一个轻量的 `ToolRuntimeContext` 抽象，由 `AgentLoop` 在每次工具调用时把当前回合的 `RuntimeState` 适配进去。用户可以重新启动应用，观察到启动日志不再出现 `No qualifying bean of type 'com.pixflow.harness.loop.RuntimeState'`。同时，通过测试可以证明 `plan` / `plan_exit` 修改的是当前回合状态，而不是一个共享单例。

## Progress

- [x] (2026-07-09 00:25+08:00) 已读取 `PLANS.md`，确认 ExecPlan 必须自包含、包含进度/发现/决策/回顾、提供具体命令和可观察验收。
- [x] (2026-07-09 00:25+08:00) 已读取 `docs/design-docs/index.md`、当前 active exec plans、`docs/design-docs/agent/agent.md` 中 Plan 模式与 `RuntimeState.metadata.planMode` 相关内容。
- [x] (2026-07-09 00:29+08:00) 已定位当前报错链路：`AgentAutoConfiguration` 组件扫描注册 `AgentPlanExitToolHandler`；handler 构造函数强制注入 `RuntimeState`；`LoopAutoConfiguration` 明确不提供 `RuntimeState` Bean。
- [x] (2026-07-09 00:33+08:00) 已确认同类问题同时存在于 `AgentPlanToolHandler`，因此修复范围必须同时覆盖 `plan` 和 `plan_exit` 两个工具。
- [x] (2026-07-09 00:40+08:00) 新建本计划，确定重构方向为“tools 层轻量运行态抽象 + loop 层当前状态适配 + agent handler 通过 invocation 读取上下文”，而不是发布假的 `RuntimeState` Bean。
- [x] (2026-07-09 00:38+08:00) 实施 `pixflow-tools` 的 `ToolRuntimeContext` 抽象，并将其接入 `ToolExecutionContext` 与 `ToolInvocation`。
- [x] (2026-07-09 00:38+08:00) 调整 `AgentLoop` 在构造 `ToolExecutionContext` 时绑定当前 `RuntimeState`。
- [x] (2026-07-09 00:38+08:00) 调整 `PlanModeController`、`AgentPlanToolHandler`、`AgentPlanExitToolHandler`，移除 handler 对 `RuntimeState` 的构造注入。
- [x] (2026-07-09 00:42+08:00) 增加单元测试和 ArchUnit 边界测试，证明 handler 通过 `ToolInvocation.runtimeContext()` 修改当前上下文，且禁止 ToolHandler 依赖 `RuntimeState` 回归。
- [x] (2026-07-09 00:47+08:00) 运行 focused、模块级和 app 级验证命令，并把实际结果写入 `Outcomes & Retrospective`。

## Surprises & Discoveries

- Observation: `LoopAutoConfiguration` 明确写明不提供 `RuntimeState` Bean，这说明当前报错不是缺少 `@Component` 注解，而是把 per-turn 对象错误放入 Spring 构造注入链。
  Evidence: `pixflow-loop/src/main/java/com/pixflow/harness/loop/config/LoopAutoConfiguration.java` 注释写明“`RuntimeState` 不同 conversationId 不能共享实例，必须由调用方在回合入口各自构造；本配置不提供 `RuntimeState` bean。”

- Observation: `RuntimeState` 的类注释明确说明它是“单会话可变运行态，回合内线程封闭”，并且 `metadata` 承载 `planMode`。把它注册成单例 Bean 会造成多个会话共享 `conversationId`、`traceId`、`turnNo` 和 Plan 模式状态。
  Evidence: `pixflow-loop/src/main/java/com/pixflow/harness/loop/RuntimeState.java` 注释说明 `metadata` 承载 `planMode`，并且调用方为每个 conversationId 在请求入口构造新实例。

- Observation: `AgentOrchestrator` 已经在每个回合现场创建 `RuntimeState`，并在构造 `AgentLoop` 时用 lambda 绑定当前 `PlanModeView`。这证明现有设计已经承认 Plan 模式视图不能是单例 Bean。
  Evidence: `pixflow-agent/src/main/java/com/pixflow/agent/AgentOrchestrator.java` 中 `newState(String conversationId)` 每次 `new RuntimeState()`；`buildAgentLoop` 注释写明 `PlanModeView` 不能是单例 bean，因为 `RuntimeState` 是 per-conversation 实例。

- Observation: 当前错误不只影响 `AgentPlanExitToolHandler`。`AgentPlanToolHandler` 也在构造函数中注入 `RuntimeState`，如果只修 `plan_exit`，启动或后续装配仍会在 `plan` 工具处复现同类问题。
  Evidence: `pixflow-agent/src/main/java/com/pixflow/agent/planmode/AgentPlanToolHandler.java` 和 `AgentPlanExitToolHandler.java` 都持有 `private final RuntimeState runtimeState`。

- Observation: 不能把 `RuntimeState` 直接加入 `ToolInvocation`，因为 `pixflow-tools` 当前不依赖 `pixflow-loop`，而 `pixflow-loop` 依赖 `pixflow-tools`。如果 `tools` 反向依赖 `loop`，会破坏模块依赖方向。
  Evidence: `pixflow-tools/pom.xml` 依赖 common、permission、hooks、infra-storage、Spring autoconfigure 等，但不依赖 `pixflow-loop`；`pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java` 已导入并使用 `ToolExecutionContext`、`ToolExecutor` 等 tools 类型。

## Decision Log

- Decision: 不给 `RuntimeState` 加 `@Component`，也不在任何 auto-configuration 中发布 `RuntimeState` Bean。
  Rationale: `RuntimeState` 是每回合可变状态，包含 conversationId、turnNo、traceId、usage、transition 和 metadata。单例 Bean 会造成跨会话状态污染，并引入并发数据竞争。当前启动失败正是生命周期边界错误，不应通过发布错误生命周期的 Bean 掩盖。
  Date/Author: 2026-07-09 / Codex

- Decision: 不通过扩大 `@ComponentScan` 修复。
  Rationale: `RuntimeState` 本来就没有 Spring stereotype 注解，扩大扫描也不会创建正确 Bean。项目已有自动配置边界重构要求：可复用模块通过 `AutoConfiguration.imports` 明确发布稳定 Bean；per-turn 运行态对象不属于自动配置发布范围。
  Date/Author: 2026-07-09 / Codex

- Decision: 在 `pixflow-tools` 中引入不依赖 loop 的 `ToolRuntimeContext` 抽象，而不是让 `ToolInvocation` 直接引用 `RuntimeState`。
  Rationale: tools 是 loop 的下游基础模块，不能反向依赖 loop。`ToolRuntimeContext` 只暴露读取 metadata 与写入 metadata 的最小能力，可以让 plan handler 修改当前运行态，同时保持模块依赖方向为 loop -> tools、agent -> tools。
  Date/Author: 2026-07-09 / Codex

- Decision: `AgentLoop` 在每次执行工具调用时把当前 `RuntimeState` 适配成 `ToolRuntimeContext` 并放入 `ToolExecutionContext`。
  Rationale: `AgentLoop` 是当前回合运行态的持有者，也是从模型 tool call 转入 harness tool execution 的边界。它最清楚当前 state 属于哪个 conversationId 和 turnNo，因此应由它把状态显式传递给工具执行链路。
  Date/Author: 2026-07-09 / Codex

- Decision: `AgentPlanToolHandler` 和 `AgentPlanExitToolHandler` 保持 Spring 单例，但只注入无状态协作 Bean `PlanModeController`，运行态从 `ToolInvocation.runtimeContext()` 读取。
  Rationale: handler 本身是工具行为的单例实现，不应持有特定会话的状态。每次调用时从 invocation 读取当前上下文，符合工具调用的生命周期。
  Date/Author: 2026-07-09 / Codex

- Decision: 增加 ArchUnit 测试，禁止 `com.pixflow.agent..` 下 Spring 组件构造函数直接依赖 `RuntimeState`。
  Rationale: 这类错误在编译期不会失败，只有 Spring 启动时才暴露。架构测试可以把生命周期边界变成自动化约束，避免未来新增 handler 或 service 重复犯错。
  Date/Author: 2026-07-09 / Codex

## Outcomes & Retrospective

本计划已实施完成。`pixflow-tools` 新增 `ToolRuntimeContext`，`ToolExecutionContext` 与 `ToolInvocation` 均携带该上下文并保留旧签名重载构造函数；`RegistryToolExecutor` 把 execution context 中的 runtime context 传给 handler。`AgentLoop` 在每次工具执行边界把当前回合的 `RuntimeState.metadata` 适配为 `ToolRuntimeContext`，保持 `pixflow-tools` 不依赖 `pixflow-loop`。

`AgentPlanToolHandler` 与 `AgentPlanExitToolHandler` 已移除 `RuntimeState` 字段和构造参数，改为从 `ToolInvocation.runtimeContext()` 读取当前回合上下文；`PlanModeController` 保留既有 `RuntimeState` 方法用于兼容，同时新增 `ToolRuntimeContext` 重载并复用同一套读写逻辑。新增 `PlanModeToolHandlerTest` 覆盖 `plan` / `plan_exit` 写回 fake runtime metadata 和缺失 runtime context 时返回工具错误；新增 `AgentLoopToolRuntimeContextTest` 证明 loop 构造的 `ToolExecutionContext.runtimeContext()` 会写回当前 `RuntimeState.metadata`；`AgentArchitectureTest` 新增规则禁止 `ToolHandler` 实现依赖 `RuntimeState`。

实际验证结果：

    mvn -pl pixflow-loop -am "-Dtest=AgentLoopToolRuntimeContextTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
    [INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS

    mvn -pl pixflow-agent -am "-Dtest=PlanModeControllerTest,PlanModeToolHandlerTest,AgentArchitectureTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
    [INFO] Tests run: 17, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS

    mvn -pl pixflow-tools,pixflow-loop,pixflow-agent -am test
    [INFO] PixFlow Tools ...................................... SUCCESS
    [INFO] PixFlow Loop ....................................... SUCCESS
    [INFO] PixFlow Agent ...................................... SUCCESS
    [INFO] Tests run: 40, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS

    mvn -pl pixflow-app -am -DskipTests compile
    [INFO] PixFlow App ........................................ SUCCESS
    [INFO] BUILD SUCCESS

未运行 `mvn -pl pixflow-app -am spring-boot:run` 常驻启动验证；app 聚合编译和跨模块测试已证明当前代码不再需要 Spring 容器提供 `RuntimeState` Bean。源码检查 `rg -n "RuntimeState runtimeState|private final RuntimeState runtimeState|RuntimeState\\)" pixflow-agent\\src\\main\\java` 未命中生产 handler，仅剩 `PromptProvider` 文档注释中提到 `RuntimeState` 方法签名。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。这是一个 Java 21 + Spring Boot 3.5 多 Maven 模块项目。`pixflow-app` 是最终启动应用，`pixflow-agent` 是 Agent 装配层，`pixflow-loop` 是单回合 think-act-observe 执行循环，`pixflow-tools` 是工具注册、权限校验与 handler 调用的基础模块。

Spring Bean 是由 Spring 容器创建和管理的对象。通常情况下，`@Component`、`@Service`、`@Configuration` 或 `@Bean` 会让对象在应用启动时被创建。Spring 单例 Bean 默认在整个应用进程内共享同一个实例。

`RuntimeState` 是 `pixflow-loop/src/main/java/com/pixflow/harness/loop/RuntimeState.java` 中的可变运行态对象。它不是 Spring Bean。它包含当前会话回合的 `conversationId`、`turnNo`、`traceId`、token usage、transition 状态和开放的 `metadata`。这里的“metadata”是一个扩展 Map，Plan 模式使用 `metadata["planMode"]` 保存当前是否处于 Plan 模式。

Plan 模式是 agent 的一种只读规划模式。模型调用 `plan` 工具进入 Plan 模式，调用 `plan_exit` 退出 Plan 模式并可保存草拟计划。Plan 模式的状态不是数据库持久状态，也不是全局开关；它是当前回合 `RuntimeState.metadata` 的一部分。

当前相关文件如下。

`pixflow-agent/src/main/java/com/pixflow/agent/planmode/AgentPlanToolHandler.java` 是 `plan` 工具 handler。当前错误设计是它作为 Spring 单例注入 `RuntimeState`。

`pixflow-agent/src/main/java/com/pixflow/agent/planmode/AgentPlanExitToolHandler.java` 是 `plan_exit` 工具 handler。当前启动报错点是它的构造函数第二个参数 `RuntimeState runtimeState`。

`pixflow-agent/src/main/java/com/pixflow/agent/planmode/PlanModeController.java` 是 Plan 模式状态机，当前通过 `enter(RuntimeState)`、`exit(RuntimeState, String)`、`readPlanMode(RuntimeState)` 修改或读取 `RuntimeState.metadata`。

`pixflow-agent/src/main/java/com/pixflow/agent/AgentOrchestrator.java` 是 Agent 回合入口。它在 `newState(String conversationId)` 中为每个会话回合创建新的 `RuntimeState`，并在 `buildAgentLoop` 中把当前 state 传给 `AgentLoop`。

`pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java` 是单回合执行循环。它持有当前 `RuntimeState`，在模型返回 tool calls 后构造 `ToolExecutionContext` 并调用 `ToolExecutor.execute(...)`。

`pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolExecutionContext.java` 是工具执行时传给 executor 的上下文。当前它包含权限、hooks、result storage、trace sink、PlanModeView、executor 和 hiddenTools，但不包含可写运行态上下文。

`pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolInvocation.java` 是最终传给具体 `ToolHandler.handle(...)` 的调用对象。当前它包含 toolCallId、toolName、arguments、conversationId、turnNo、traceId、runtimeScope 和 metadata，但没有当前回合运行态写入能力。

`pixflow-tools/src/main/java/com/pixflow/harness/tools/RegistryToolExecutor.java` 是默认工具执行器。它把 `ToolCall` 转成 `ToolInvocation`，然后调用 `descriptor.handler().handle(invocation)`。

## Plan of Work

第一阶段是在 `pixflow-tools` 增加轻量运行态抽象。新增文件 `pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolRuntimeContext.java`。这个接口不引用 `RuntimeState`，只暴露两个能力：读取当前 metadata 快照，以及写入一个 metadata 键值。建议接口形状如下：

    package com.pixflow.harness.tools;

    import java.util.Map;

    public interface ToolRuntimeContext {
        Map<String, Object> metadata();

        default Object metadataOrDefault(String key, Object defaultValue) {
            Object value = metadata().get(key);
            return value == null ? defaultValue : value;
        }

        void putMetadata(String key, Object value);

        static ToolRuntimeContext unavailable() {
            return UnavailableToolRuntimeContext.INSTANCE;
        }
    }

`UnavailableToolRuntimeContext` 可以是同文件内的 package-private final class，也可以是单独文件。它的 `metadata()` 返回 `Map.of()`，`putMetadata(...)` 抛出 `IllegalStateException("Tool runtime context is not available")`。不要让 unavailable 版本静默 no-op，因为 plan 工具需要明确失败，而不是假装进入 Plan 模式。

第二阶段是把这个抽象接入 tools 执行链路。修改 `ToolExecutionContext` record，新增字段 `ToolRuntimeContext runtimeContext`。为了降低调用点改动风险，给 record 增加一个保留旧参数列表的重载构造函数，旧构造函数内部把 `runtimeContext` 设置为 `ToolRuntimeContext.unavailable()`。在 canonical constructor 中，如果传入 null，也归一化成 unavailable。

修改 `ToolInvocation` record，新增字段 `ToolRuntimeContext runtimeContext`。同样保留旧参数列表的重载构造函数，旧构造函数使用 `ToolRuntimeContext.unavailable()`。这样现有测试和非 plan 工具不会被一次性迫使全部改造。

修改 `RegistryToolExecutor.invokeHandler(...)`。构造 `ToolInvocation` 时，把 `context.runtimeContext()` 传入 invocation。这样任何 handler 都可以通过 invocation 拿到当前回合运行态，但只有需要修改运行态的 handler 才会使用它。

第三阶段是在 `AgentLoop` 的工具执行边界绑定当前 state。修改 `pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java` 的 `executeToolCalls(...)`。在创建 `ToolExecutionContext` 前，构造一个匿名 `ToolRuntimeContext`：

    ToolRuntimeContext runtimeContext = new ToolRuntimeContext() {
        @Override
        public Map<String, Object> metadata() {
            return state.metadata();
        }

        @Override
        public void putMetadata(String key, Object value) {
            state.putMetadata(key, value);
        }
    };

然后把这个 `runtimeContext` 作为新增参数传给 `ToolExecutionContext`。这一步是核心机制：`AgentLoop` 仍然是 `RuntimeState` 的唯一持有者，tools 和 agent handler 只看到一个小接口。

第四阶段是调整 Plan 模式控制器和 handler。修改 `PlanModeController`，保留现有 `enter(RuntimeState)`、`exit(RuntimeState, String)`、`readPlanMode(RuntimeState)` 方法以兼容现有调用和测试，同时新增重载：

    public void enter(ToolRuntimeContext context)
    public void exit(ToolRuntimeContext context, String draftPlan)
    public PlanModeState readPlanMode(ToolRuntimeContext context)

三个新方法使用同样的 key：`planMode` 和 `lastPlanDraft`。读取逻辑应提取成私有 helper，避免 RuntimeState 与 ToolRuntimeContext 两条路径行为分叉。

修改 `AgentPlanToolHandler`。删除字段 `RuntimeState runtimeState`，构造函数只保留 `PlanModeController controller`。`handle(...)` 中调用 `controller.enter(invocation.runtimeContext())`。如果 runtime context 不可用，controller 或 unavailable context 会抛 `IllegalStateException`，handler 按现有逻辑返回 error metadata。

修改 `AgentPlanExitToolHandler`。同样删除 `RuntimeState` 字段和构造参数，在 `handle(...)` 中调用 `controller.exit(invocation.runtimeContext(), draftPlan)`。返回 metadata 保持 `plan_mode = OFF` 和可选 `draft_plan`。

第五阶段是增加测试守护。新增或更新以下测试。

在 `pixflow-agent/src/test/java/com/pixflow/agent/planmode/` 下新增 `AgentPlanToolHandlerTest.java` 和 `AgentPlanExitToolHandlerTest.java`，或者合并成一个 `PlanModeToolHandlerTest.java`。测试用一个 fake `ToolRuntimeContext` 持有 `LinkedHashMap<String,Object>`，构造 `ToolInvocation` 调 handler。测试应覆盖：调用 `plan` 后 metadata 中 `planMode` 为 `true`；调用 `plan_exit` 后 metadata 中 `planMode` 为 `false` 且 `lastPlanDraft` 被保存；没有 runtime context 时 handler 返回 error metadata，而不是抛出未捕获异常。

在 `pixflow-loop/src/test/java/com/pixflow/harness/loop/` 现有 loop 测试中补一个小测试，或新增 `AgentLoopToolRuntimeContextTest`。目标是证明 `AgentLoop` 构造的 `ToolExecutionContext.runtimeContext()` 可以写回当前 `RuntimeState.metadata`。如果完整 loop 测试成本较高，可以先在 `FakeToolExecutor` 中记录 context，然后断言 context 不为 unavailable，并且调用 `putMetadata("planMode", true)` 后 `loop.state().metadataOrDefault("planMode", false)` 为 `true`。

在 `pixflow-agent/src/test/java/com/pixflow/agent/architecture/AgentArchitectureTest.java` 增加规则：被 Spring stereotype 注解标记的 agent 类，其构造函数或字段不应依赖 `com.pixflow.harness.loop.RuntimeState`。最小可接受规则是 `noClasses().that().resideInAPackage("com.pixflow.agent..").and().areAnnotatedWith(Component.class).should().dependOnClassesThat().haveFullyQualifiedName("com.pixflow.harness.loop.RuntimeState")`。如果这会误伤 `AgentOrchestrator`，则把规则收窄到 `com.pixflow.agent.planmode..` 和 `com.pixflow.agent.*.tools..` 的 handler 包，或使用 ArchUnit 的 constructor parameter 条件只禁止构造注入。最终规则必须覆盖本次回归点。

第六阶段是验证 app 启动装配。先运行 focused tests，再运行 app 编译和可选启动。不要为了启动成功而修改 app 的 `@SpringBootApplication` scanBasePackages，也不要新增 `RuntimeState` Bean。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。

先确认当前错误形态：

    rg -n "RuntimeState runtimeState|private final RuntimeState runtimeState|AgentPlan.*ToolHandler" pixflow-agent/src/main/java/com/pixflow/agent/planmode
    rg -n "RuntimeState 不同 conversationId|本配置不提供" pixflow-loop/src/main/java/com/pixflow/harness/loop/config/LoopAutoConfiguration.java

预期当前能看到 `AgentPlanToolHandler` 和 `AgentPlanExitToolHandler` 都注入 `RuntimeState`，并看到 `LoopAutoConfiguration` 明确不提供 `RuntimeState` Bean。

新增 `ToolRuntimeContext`：

    pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolRuntimeContext.java

内容按 `Plan of Work` 第一阶段实现。保持接口不依赖 `com.pixflow.harness.loop`。

修改 `ToolExecutionContext`：

    pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolExecutionContext.java

新增 record 字段 `ToolRuntimeContext runtimeContext`。canonical constructor 中归一化 null。增加旧签名重载构造函数以兼容旧调用点。

修改 `ToolInvocation`：

    pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolInvocation.java

新增 record 字段 `ToolRuntimeContext runtimeContext`。canonical constructor 中归一化 null。增加旧签名重载构造函数以兼容旧测试。

修改 `RegistryToolExecutor`：

    pixflow-tools/src/main/java/com/pixflow/harness/tools/RegistryToolExecutor.java

在 `invokeHandler(...)` 中构造 `ToolInvocation` 时追加 `context.runtimeContext()`。

修改 `AgentLoop`：

    pixflow-loop/src/main/java/com/pixflow/harness/loop/AgentLoop.java

在 `executeToolCalls(...)` 中创建 `ToolRuntimeContext` 匿名适配器，并把它传入 `ToolExecutionContext`。需要新增 import `com.pixflow.harness.tools.ToolRuntimeContext`。

修改 `PlanModeController`：

    pixflow-agent/src/main/java/com/pixflow/agent/planmode/PlanModeController.java

新增基于 `ToolRuntimeContext` 的三个重载方法，并抽取共享读取逻辑。保留现有 RuntimeState 方法。

修改两个 handler：

    pixflow-agent/src/main/java/com/pixflow/agent/planmode/AgentPlanToolHandler.java
    pixflow-agent/src/main/java/com/pixflow/agent/planmode/AgentPlanExitToolHandler.java

删除 `RuntimeState` import、字段和构造参数。`handle(...)` 通过 `invocation.runtimeContext()` 调 controller。

新增测试后，先跑 focused tests：

    mvn -pl pixflow-agent -Dtest=PlanModeControllerTest,PlanModeToolHandlerTest,AgentArchitectureTest test

如果把 handler 测试拆成两个类，则命令改为：

    mvn -pl pixflow-agent -Dtest=PlanModeControllerTest,AgentPlanToolHandlerTest,AgentPlanExitToolHandlerTest,AgentArchitectureTest test

再跑跨模块验证：

    mvn -pl pixflow-tools,pixflow-loop,pixflow-agent -am test

最后跑 app 聚合编译：

    mvn -pl pixflow-app -am -DskipTests compile

如果本机 MySQL、Redis、MinIO、RocketMQ、Qdrant 等外部依赖已就绪，可继续启动：

    mvn -pl pixflow-app -am spring-boot:run

可接受结果是应用成功启动，或失败推进到真实外部依赖连接错误。不可接受结果是仍然出现：

    No qualifying bean of type 'com.pixflow.harness.loop.RuntimeState' available

收尾检查：

    rg -n "RuntimeState runtimeState|private final RuntimeState runtimeState" pixflow-agent/src/main/java

预期输出为空，或仅命中非 Spring 单例、非 handler 的测试/文档位置。生产 Java 中 `AgentPlanToolHandler` 与 `AgentPlanExitToolHandler` 不应再命中。

## Validation and Acceptance

最低验收是 focused tests 通过，证明 Plan 模式 handler 不再依赖 Spring 注入 `RuntimeState`，并能通过 `ToolInvocation.runtimeContext()` 修改当前上下文。

运行：

    mvn -pl pixflow-agent -Dtest=PlanModeControllerTest,PlanModeToolHandlerTest,AgentArchitectureTest test

预期 Maven 末尾出现：

    [INFO] BUILD SUCCESS

如果测试类拆分，使用拆分后的 `-Dtest` 列表，并在本节更新实际命令。

跨模块验收是：

    mvn -pl pixflow-tools,pixflow-loop,pixflow-agent -am test

预期 `pixflow-tools`、`pixflow-loop`、`pixflow-agent` 均 BUILD SUCCESS。这证明 tools 的接口变更没有破坏 loop 与 agent。

app 装配验收是：

    mvn -pl pixflow-app -am -DskipTests compile

预期 BUILD SUCCESS。这个命令证明 app 的 Maven reactor 可以编译当前工作区模块，不再停在 `RuntimeState` 构造注入缺口。

启动日志验收是可选但推荐的。运行 `mvn -pl pixflow-app -am spring-boot:run` 后，不应再看到：

    UnsatisfiedDependencyException: Error creating bean with name 'agentPlanExitToolHandler'
    No qualifying bean of type 'com.pixflow.harness.loop.RuntimeState' available

如果启动失败于 Redis、MySQL、MinIO、RocketMQ、Qdrant 或模型 provider 连接错误，应确认失败栈与 `RuntimeState` Bean 无关；外部服务连接失败不算本计划失败。

行为验收是：在 handler 单测中，调用 `plan` 后 fake runtime metadata 包含 `planMode=true`；调用 `plan_exit` 后 fake runtime metadata 包含 `planMode=false`，并在配置允许保留草稿时包含 `lastPlanDraft=<输入草稿>`。这证明 Plan 模式仍修改当前运行态，而不是只绕过启动报错。

## Idempotence and Recovery

本计划的改动是增量式的，可重复执行。重复添加 `ToolRuntimeContext` 时，应以最终文件内容为准，不要创建第二个类似接口。重复修改 record 构造函数时，保留一个 canonical constructor 和一个旧签名重载构造函数即可。

如果编译失败提示某处 `new ToolExecutionContext(...)` 参数数量不匹配，优先检查是否已添加旧签名重载构造函数。不要为了快速修复去修改所有调用点，除非这些调用点确实需要运行态上下文。

如果编译失败提示某处 `new ToolInvocation(...)` 参数数量不匹配，同样检查是否已添加旧签名重载构造函数。非 plan 工具不需要感知 runtime context。

如果 handler 测试里 unavailable runtime context 直接抛出异常导致测试失败，应保持 handler 的现有错误处理风格：捕获 `IllegalStateException` 并返回 `ToolHandlerOutput`，metadata 中包含 `error=true`。不要让工具 handler 把内部异常直接冒泡成 loop 级失败。

如果 app 启动仍然报 `RuntimeState` 缺 Bean，运行：

    rg -n "RuntimeState" pixflow-agent/src/main/java

检查是否还有 Spring 单例组件构造函数或字段注入了 `RuntimeState`。修复原则仍是移除构造注入，改为从当前调用上下文读取，不要注册 `RuntimeState` Bean。

如果需要回退本计划的代码改动，删除新增 `ToolRuntimeContext.java`，恢复 `ToolExecutionContext`、`ToolInvocation`、`RegistryToolExecutor`、`AgentLoop`、`PlanModeController` 和两个 handler 到修改前状态，并删除新增测试。回退后当前 `RuntimeState` Bean 启动错误会复现，这是预期。不要回退无关工作树改动。

## Artifacts and Notes

当前错误日志关键段如下：

    UnsatisfiedDependencyException: Error creating bean with name 'agentPlanExitToolHandler'
    Unsatisfied dependency expressed through constructor parameter 1:
    No qualifying bean of type 'com.pixflow.harness.loop.RuntimeState' available

当前代码证据如下：

    pixflow-agent/src/main/java/com/pixflow/agent/planmode/AgentPlanExitToolHandler.java
    private final RuntimeState runtimeState;
    public AgentPlanExitToolHandler(PlanModeController controller, RuntimeState runtimeState)

    pixflow-agent/src/main/java/com/pixflow/agent/planmode/AgentPlanToolHandler.java
    private final RuntimeState runtimeState;
    public AgentPlanToolHandler(PlanModeController controller, RuntimeState runtimeState)

    pixflow-loop/src/main/java/com/pixflow/harness/loop/config/LoopAutoConfiguration.java
    本配置不提供 RuntimeState bean。

目标形态如下：

    pixflow-tools/src/main/java/com/pixflow/harness/tools/ToolRuntimeContext.java
    public interface ToolRuntimeContext {
        Map<String, Object> metadata();
        void putMetadata(String key, Object value);
    }

    pixflow-agent/src/main/java/com/pixflow/agent/planmode/AgentPlanExitToolHandler.java
    public AgentPlanExitToolHandler(PlanModeController controller)

    pixflow-agent/src/main/java/com/pixflow/agent/planmode/AgentPlanToolHandler.java
    public AgentPlanToolHandler(PlanModeController controller)

实施完成后，`rg -n "RuntimeState runtimeState|private final RuntimeState runtimeState" pixflow-agent/src/main/java` 不应命中生产 handler。

## Interfaces and Dependencies

本计划新增或确认以下接口和依赖关系。

`pixflow-tools` 新增接口：

    package com.pixflow.harness.tools;

    public interface ToolRuntimeContext {
        Map<String, Object> metadata();
        Object metadataOrDefault(String key, Object defaultValue);
        void putMetadata(String key, Object value);
        static ToolRuntimeContext unavailable();
    }

`ToolExecutionContext` 新增字段：

    ToolRuntimeContext runtimeContext

`ToolInvocation` 新增字段：

    ToolRuntimeContext runtimeContext

`RegistryToolExecutor` 必须把 `ToolExecutionContext.runtimeContext()` 传入 `ToolInvocation`。

`AgentLoop` 必须把当前 `RuntimeState` 适配成 `ToolRuntimeContext`，但 `pixflow-tools` 不得依赖 `pixflow-loop`。依赖方向保持：

    pixflow-loop -> pixflow-tools
    pixflow-agent -> pixflow-tools
    pixflow-agent -> pixflow-loop

不得新增：

    pixflow-tools -> pixflow-loop

`PlanModeController` 必须同时支持：

    enter(RuntimeState state)
    exit(RuntimeState state, String draftPlan)
    readPlanMode(RuntimeState state)
    enter(ToolRuntimeContext context)
    exit(ToolRuntimeContext context, String draftPlan)
    readPlanMode(ToolRuntimeContext context)

`AgentPlanToolHandler` 和 `AgentPlanExitToolHandler` 的构造函数不得包含 `RuntimeState` 参数。它们只能通过 `ToolInvocation.runtimeContext()` 修改当前运行态。

## Revision Notes

2026-07-09 / Codex: 新建本 ExecPlan。原因是 `AgentPlanExitToolHandler` 在 Spring 启动期注入 per-turn `RuntimeState`，导致 `No qualifying bean`。计划选择 tools 层轻量 `ToolRuntimeContext` 抽象、loop 层当前 state 适配、agent handler 通过 invocation 读取上下文的重构机制，避免发布错误生命周期的 `RuntimeState` Bean，并通过测试和架构约束防止同类问题复发。

2026-07-09 / Codex: 完成本 ExecPlan 的代码实施与验证。新增 `ToolRuntimeContext` 并贯通 `ToolExecutionContext` / `ToolInvocation` / `RegistryToolExecutor` / `AgentLoop`；`plan` 与 `plan_exit` handler 改为从 invocation runtime context 修改当前回合状态；新增 handler、loop 和 ArchUnit 测试；`mvn -pl pixflow-tools,pixflow-loop,pixflow-agent -am test` 与 `mvn -pl pixflow-app -am -DskipTests compile` 均 BUILD SUCCESS。

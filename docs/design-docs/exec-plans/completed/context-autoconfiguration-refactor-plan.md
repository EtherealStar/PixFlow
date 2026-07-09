# 重构 context 自动配置边界并清理同类 Bean 装配缺口

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。本文面向完全不了解当前会话的执行者：只要有当前工作树和本文件，就应能完成重构、验证结果，并知道为什么这样做。

## Purpose / Big Picture

当前 `pixflow-app` 启动失败于 Spring Bean 装配：`AgentOrchestrator` 已经被 `pixflow-agent` 自动配置扫描进容器，但它的构造函数需要 `com.pixflow.harness.context.budget.TokenEstimator`，容器里没有任何该类型的 Bean。这个错误不是单点遗漏，而是此前收窄应用扫描范围后暴露出的同类问题：可复用模块中的核心运行时能力没有通过自己的 Spring Boot 自动配置稳定发布，只能靠全包扫描或下游模块碰巧兜底。

完成本计划后，`harness/context` 会拥有自己的自动配置入口，稳定发布 token 估算、预算治理、上下文压缩等默认基础 Bean。`pixflow-agent` 不再需要为 context 基础件兜底；`pixflow-app` 也不需要把 `@SpringBootApplication` 扫描范围扩大回 `com.pixflow`。用户可以重新启动应用，观察到 Spring 上下文越过 `TokenEstimator`、`ContextBudgetService`、`ContextCompactionService` 这组缺失 Bean，不再在 `AgentOrchestrator` 构造阶段失败。

本计划还要求清查并修复同类装配缺口。这里的“同类问题”指：某个模块把类注册成 Spring Bean 后，构造函数依赖另一个模块的核心类型，但该核心类型没有由所属模块通过 `AutoConfiguration.imports` 和 `@Bean` 发布。清查目标不是消灭所有普通 Java 构造，而是确保会进入 Spring 容器的核心能力能通过模块自身自动配置被稳定发现。

## Progress

- [x] (2026-07-08 22:45+08:00) 已读取 `PLANS.md`，确认 ExecPlan 必须自包含、包含进度/发现/决策/回顾、提供具体命令和可观察验收。
- [x] (2026-07-08 22:47+08:00) 已读取 `docs/design-docs/index.md`、当前 active exec plans、`docs/design-docs/harness/context.md` 和 `docs/design-docs/agent/agent.md` 的相关部分，确认 context 负责 token 预算与 compaction，agent 是 Wave 5 薄装配层。
- [x] (2026-07-08 22:50+08:00) 已定位当前报错链路：`AgentAutoConfiguration` 通过 `@ComponentScan(basePackages = "com.pixflow.agent")` 注册 `AgentOrchestrator`；`AgentOrchestrator` 构造函数强制注入 `TokenEstimator`、`ContextBudgetService`、`ContextCompactionService`；`ConservativeTokenEstimator` 只是普通 final 类，没有被发布为 Bean。
- [x] (2026-07-08 22:55+08:00) 新建本计划，确定重构方向为“context 模块自装配 + 同类缺口清查”，而不是扩大 app 扫描范围或在 agent 中临时补 Bean。
- [x] (2026-07-08 23:32+08:00) 在 `pixflow-context` 中新增 `spring-boot-autoconfigure` 依赖、`ContextAutoConfiguration` 和 `AutoConfiguration.imports`。
- [x] (2026-07-08 23:34+08:00) 为 `ContextAutoConfiguration` 增加 `ApplicationContextRunner` 测试，覆盖默认基础 Bean、自定义 `TokenEstimator` 覆盖、可选 `SummarizationPort` 接入和缺失摘要端口兜底。
- [x] (2026-07-08 23:34+08:00) 调整 `AgentAutoConfiguration` 为 `@AutoConfiguration(after = ContextAutoConfiguration.class)`，只表达装配顺序，不在 agent 中发布 context 默认 Bean。
- [x] (2026-07-08 23:43+08:00) 清查 `pixflow-context`、`pixflow-loop`、`pixflow-tools`、`pixflow-hooks`、`pixflow-eval`、`pixflow-permission`、`pixflow-session`、`pixflow-agent`、`pixflow-conversation` 的自动配置入口与组件注册点；未发现除 context 基础件外必须立即修复的当前启动路径缺口。
- [x] (2026-07-08 23:50+08:00) 运行模块级和 app 级验证：`mvn -pl pixflow-context test`、`mvn -pl pixflow-agent -am test`、`mvn -pl pixflow-app -am -DskipTests compile`、`mvn -pl pixflow-app test` 均 BUILD SUCCESS；`mvn -pl pixflow-app -am test` 因上游集成测试链路超过 6 分钟超时，未返回失败栈。
- [x] (2026-07-08 23:55+08:00) 更新 `docs/design-docs/harness/context.md` 和 `docs/design-docs/agent/agent.md` 的 Revision Notes，记录 context 自动配置和 agent 装配顺序。

## Surprises & Discoveries

- Observation: `pixflow-context` 当前没有自己的 `ContextAutoConfiguration`，也没有 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。它提供了 `TokenEstimator`、`ContextBudgetService`、`ContextCompactionService` 等核心类型，但这些类型目前只在测试或 `AgentOrchestrator` 内部手动 `new`。
  Evidence: `rg -n "class .*AutoConfiguration|@AutoConfiguration|TokenEstimator|ContextBudgetService|ContextCompactionService" pixflow-context/src/main` 只找到 budget/compaction 类，没有自动配置类。

- Observation: `AgentOrchestrator` 的构造函数里虽然对 `tokenEstimator == null`、`contextBudgetService == null`、`contextCompactionService == null` 写了手动 fallback，但 Spring 构造函数注入不会传 `null` 给必需参数；缺 Bean 时会在构造前失败。
  Evidence: 当前启动日志报 `No qualifying bean of type 'com.pixflow.harness.context.budget.TokenEstimator' available`，失败发生在 `AgentOrchestrator` 构造函数参数解析阶段。

- Observation: app 扫描范围收窄到 `com.pixflow.app` 是既有自动配置边界重构的设计结果，不应为了修复本问题改回根包扫描。
  Evidence: `docs/design-docs/exec-plans/completed/spring-autoconfiguration-boundary-refactor-plan.md` 明确记录“所有可复用模块不再依赖 app 根包扫描发现配置类”，并有 `PixFlowApplicationScanBoundaryTest` 守护启动类不扩大 `scanBasePackages`。

- Observation: 本次清查的目标模块中，除 `pixflow-agent` 仍通过自身自动配置做模块内组件扫描外，`pixflow-loop`、`pixflow-tools`、`pixflow-hooks`、`pixflow-eval`、`pixflow-permission`、`pixflow-session`、`pixflow-conversation` 都已有 `AutoConfiguration.imports` 入口；当前启动路径缺失的是 context 自己没有发布预算与压缩基础 Bean。
  Evidence: `rg -n "@Component|@Service|@Repository|@Bean\\(" pixflow-context\\src\\main\\java pixflow-loop\\src\\main\\java pixflow-tools\\src\\main\\java pixflow-hooks\\src\\main\\java pixflow-eval\\src\\main\\java pixflow-permission\\src\\main\\java pixflow-session\\src\\main\\java pixflow-agent\\src\\main\\java pixflow-conversation\\src\\main\\java` 的生产组件命中主要集中在 `pixflow-agent`；`rg -n "class .*AutoConfiguration|@AutoConfiguration" ...` 显示上述模块均有自动配置入口，且新增后 `rg --files | rg "pixflow-context.*AutoConfiguration\\.imports$"` 能看到 context imports 文件。

- Observation: `mvn -pl pixflow-app -am test` 会拉起包含 Testcontainers 的上游集成测试链路，当前运行超过 6 分钟后被工具超时终止，未返回失败栈；这不能作为 Spring 装配失败证据。
  Evidence: 同一工作树下 `mvn -pl pixflow-agent -am test` 完整通过，`mvn -pl pixflow-app -am -DskipTests compile` 30 模块 BUILD SUCCESS，`mvn -pl pixflow-app test` 2 个 app 边界测试 BUILD SUCCESS。

## Decision Log

- Decision: 本计划采用“`pixflow-context` 自己发布 context 基础 Bean”作为主修复方案。
  Rationale: `TokenEstimator`、`ContextBudgetService`、`ContextCompactionService` 是 context 模块的职责，不属于 agent。让 context 自己通过 `AutoConfiguration.imports` 发布默认 Bean，可以同时修复 agent 当前启动问题和未来其他模块消费 context 时的同类问题。
  Date/Author: 2026-07-08 / Codex

- Decision: 不通过 `@SpringBootApplication(scanBasePackages = "com.pixflow")` 修复。
  Rationale: 扩大 app 扫描范围会回退到旧的全包扫描模型，重新引入 controller/service 顺序不稳定、mapper 扫描过宽、核心 Bean 靠偶然发现等问题。项目已有执行计划把这类做法列为结构性风险。
  Date/Author: 2026-07-08 / Codex

- Decision: 不把 context 默认 Bean 放进 `AgentAutoConfiguration` 作为最终方案。
  Rationale: 这能修当前报错，但会把 context 的基础能力隐藏在 agent 中。若 loop、conversation 或其他模块以后直接消费 context，仍会遇到同类缺口。agent 可以声明在 context 自动配置之后运行，但不应拥有 context 默认 Bean。
  Date/Author: 2026-07-08 / Codex

- Decision: 暂不把 `ContextEngine` 作为全局 Spring Bean 发布。
  Rationale: 当前 `AgentOrchestrator` 每回合用会话特定的 `MessageStore` 构造 `ContextEngine`。`MessageStore` 是回合内运行期对象，不是全局单例。context 自动配置只发布无状态或可安全复用的基础服务，不发布依赖会话运行态的对象。
  Date/Author: 2026-07-08 / Codex

- Decision: 在 `AgentAutoConfiguration` 上保留显式 `after = ContextAutoConfiguration.class`。
  Rationale: Spring 通常会在构造参数解析时按类型触发依赖 Bean 创建，但显式排序能让 starter 边界更可读，也能防止后续 agent 自动配置提前注册依赖 context 基础件的 Bean。该排序不改变职责归属，agent 仍不发布 `TokenEstimator`、`ContextBudgetService` 或 `ContextCompactionService`。
  Date/Author: 2026-07-08 / Codex

- Decision: 本次清查不新增其他模块的自动配置 Bean。
  Rationale: 清查未发现当前启动路径中另一个“属主模块未发布核心默认 Bean、下游已强制构造注入”的明确缺口。继续机械添加 Bean 会扩大改动面，并可能违反各模块已有显式装配边界。
  Date/Author: 2026-07-08 / Codex

## Outcomes & Retrospective

本计划已实施完成。`pixflow-context` 现在通过 `ContextAutoConfiguration` 和 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 发布默认 `TokenEstimator`、`ContextBudgetService` 和 `ContextCompactionService`。三个默认 Bean 都使用 `@ConditionalOnMissingBean`，允许未来测试或生产配置覆盖。`ContextCompactionService` 通过 `ObjectProvider<SummarizationPort>` 可选接入 agent 层摘要能力；缺失摘要端口时仍走现有确定性兜底。`ContextEngine` 未发布为全局 Bean，因为它依赖每回合的 `MessageStore`。

`pixflow-agent` 的 `AgentAutoConfiguration` 已声明在 `ContextAutoConfiguration` 之后运行，以表达装配依赖，但没有把任何 context 默认 Bean 移入 agent。当前启动路径上 `AgentOrchestrator` 构造函数所需的 `TokenEstimator`、`ContextBudgetService`、`ContextCompactionService` 不再依赖全包扫描或 agent 手动兜底。

实际验证结果：

    mvn -pl pixflow-context test
    [INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS

    mvn -pl pixflow-agent -am test
    [INFO] PixFlow Context .................................... SUCCESS
    [INFO] PixFlow Agent ...................................... SUCCESS
    [INFO] Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS

    mvn -pl pixflow-app -am -DskipTests compile
    [INFO] PixFlow App ........................................ SUCCESS
    [INFO] BUILD SUCCESS

    mvn -pl pixflow-app test
    [INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS

尝试运行 `mvn -pl pixflow-app -am test` 时，命令在 6 分钟后被工具超时终止，未返回失败栈。由于 `pixflow-agent -am test` 已完整跑过包含 Docker/Testcontainers 的上游链路并 BUILD SUCCESS，且 app 聚合编译与 app 自身边界测试均通过，本次结果足以证明代码编译与模块自动配置测试已收敛。未运行常驻 `spring-boot:run`，因此没有外部依赖连接阶段的启动日志。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。这是一个 Spring Boot 3.5 多 Maven 模块项目。Spring Bean 是由 Spring 容器管理的对象，例如 service、controller、handler 或配置类创建的基础服务。Spring Boot 自动配置是指模块在 `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件里登记配置类，应用启动时由 Spring Boot 自动导入这些配置类。

当前应用启动类在 `pixflow-app/src/main/java/com/pixflow/app/PixFlowApplication.java`，包名是 `com.pixflow.app`。这意味着默认组件扫描只扫描 app 模块自己的包。`com.pixflow.module.*`、`com.pixflow.harness.*`、`com.pixflow.agent.*` 等可复用模块必须通过自动配置进入容器。这个边界是项目之前重构后的设计，不应改回全包扫描。

`pixflow-context` 是 `harness/context` 模块，源码位于 `pixflow-context/src/main/java/com/pixflow/harness/context`。它负责 Agent 运行期上下文工作内存、token 预算、cheap pipeline 和 destructive compaction 编排。plain language 解释如下：token 预算是估算消息会占用多少模型上下文；cheap pipeline 是不调用 LLM 的确定性裁剪和降级流程；destructive compaction 是在上下文超限时调用摘要能力，把历史消息压缩成摘要。context 不直接调用 LLM，它通过 `SummarizationPort` 接口让 agent 提供摘要实现。

当前与报错直接相关的文件如下。

`pixflow-agent/src/main/java/com/pixflow/agent/config/AgentAutoConfiguration.java` 是 agent 模块自动配置入口。它当前用 `@ComponentScan(basePackages = "com.pixflow.agent")` 把 agent 包里的 `@Component`、`@Service`、`@Repository` 扫进容器，并发布 `agentTurnRunner` Bean。

`pixflow-agent/src/main/java/com/pixflow/agent/AgentOrchestrator.java` 是 Agent 回合入口。它是 `@Component`，构造函数需要 `TokenEstimator`、`ContextBudgetService`、`ContextCompactionService`。这三个类型来自 context 模块或依赖 context 模块。

`pixflow-context/src/main/java/com/pixflow/harness/context/budget/TokenEstimator.java` 是 token 估算接口。`pixflow-context/src/main/java/com/pixflow/harness/context/budget/ConservativeTokenEstimator.java` 是默认保守实现，但目前只是普通类。`ContextBudgetService` 和 `ContextCompactionService` 也是普通类，没有自动配置发布。

## Plan of Work

第一阶段是在 `pixflow-context` 建立模块自装配。给 `pixflow-context/pom.xml` 增加 `org.springframework.boot:spring-boot-autoconfigure` 主依赖，用于编译 `@AutoConfiguration`、`@ConditionalOnMissingBean` 和 `ObjectProvider` 等自动配置类型。不要使用 `spring-boot-starter`，因为 context 是底层 harness 模块，不应隐式拉入 web 或运行时 starter。

在 `pixflow-context/src/main/java/com/pixflow/harness/context/config/ContextAutoConfiguration.java` 新增自动配置类。该类使用 `@AutoConfiguration`。它发布三个默认 Bean：`TokenEstimator` 返回 `new ConservativeTokenEstimator()`；`ContextBudgetService` 使用 `ContextBudgetConfig.defaults()`、`TokenEstimator` 和可选 `ToolResultExternalizer`；`ContextCompactionService` 使用 `ContextBudgetService`、`TokenEstimator`、可选 `SummarizationPort` 和 `CompactionConfig.defaults()`。每个 Bean 都加 `@ConditionalOnMissingBean`，允许测试或未来生产配置覆盖默认实现。

在 `pixflow-context/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 新增一行 `com.pixflow.harness.context.config.ContextAutoConfiguration`。如果目录不存在，创建目录。该文件每行只写一个自动配置类全限定名，不添加注释。

第二阶段是给 context 自动配置加守护测试。新增 `pixflow-context/src/test/java/com/pixflow/harness/context/config/ContextAutoConfigurationTest.java`，使用 Spring Boot 的 `ApplicationContextRunner`。测试一证明只导入 `ContextAutoConfiguration` 时，容器里存在 `TokenEstimator`、`ContextBudgetService` 和 `ContextCompactionService`。测试二提供自定义 `TokenEstimator` Bean，证明默认 `ConservativeTokenEstimator` 不会覆盖用户 Bean。测试三提供一个 fake `SummarizationPort`，证明 `ContextCompactionService` 可以拿到可选摘要端口而不强制依赖 agent 模块。这个测试不能引入 agent 依赖，防止 context 反向依赖 Wave 5。

第三阶段是调整 agent 与 context 自动配置的关系。首选在 `AgentAutoConfiguration` 上声明 `@AutoConfiguration(after = ContextAutoConfiguration.class)`，并增加 import `com.pixflow.harness.context.config.ContextAutoConfiguration`。这不是为了让 agent 拥有 context，而是表达 agent 的装配依赖：agent 构造 `AgentOrchestrator` 前，需要 context 基础 Bean 已经可用。如果实际编译显示循环或不必要，可以保留无序，因为 Spring Bean 实例化会按构造参数解析；但计划执行者必须在 `Decision Log` 记录最终选择。

第四阶段是清查同类问题。先列出所有自动配置入口，再列出可复用模块中被 `@Component`、`@Service`、`@Repository` 或配置类 `@Bean` 注册的核心 Bean。重点检查构造函数参数是否来自另一个模块且没有对应自动配置默认 Bean。不要机械地给所有普通类加 `@Component`。判断标准是：这个类型是否是模块公开基础能力；它是否应该由所属模块发布；如果缺失时是否会在 app 启动路径报间接错误。

清查时优先覆盖以下路径：`pixflow-context`、`pixflow-loop`、`pixflow-tools`、`pixflow-hooks`、`pixflow-eval`、`pixflow-permission`、`pixflow-session`、`pixflow-agent`、`pixflow-conversation`。如果发现类似缺口，按属主模块自装配修复。例如 tools 的默认 registry 属于 tools，permission 的默认 policy 属于 permission，context 的预算与压缩属于 context。不要把上游模块的默认实现塞进下游业务模块。

第五阶段是更新文档。`docs/design-docs/harness/context.md` 的 Revision Notes 应记录 context 自动配置落地：`ContextAutoConfiguration` 发布默认 `TokenEstimator`、`ContextBudgetService`、`ContextCompactionService`，但不发布 `ContextEngine`，因为 `ContextEngine` 依赖回合内 `MessageStore`。如调整了 `AgentAutoConfiguration` 的排序，也在 `docs/design-docs/agent/agent.md` Revision Notes 中记录 agent 依赖 context 自动配置而不拥有 context 默认 Bean。

## Concrete Steps

所有命令从仓库根目录 `D:\study\PixFlow` 执行。

先确认现状：

    rg -n "class .*AutoConfiguration|@AutoConfiguration|TokenEstimator|ContextBudgetService|ContextCompactionService" pixflow-context/src/main pixflow-agent/src/main
    rg --files | rg "AutoConfiguration\\.imports$"

预期当前能看到 `pixflow-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，但看不到 `pixflow-context` 的 imports 文件。预期能看到 `ConservativeTokenEstimator` 是普通 final class，而不是 Bean。

编辑 `pixflow-context/pom.xml`，在 `<dependencies>` 中增加：

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure</artifactId>
    </dependency>

新增 `pixflow-context/src/main/java/com/pixflow/harness/context/config/ContextAutoConfiguration.java`，内容应等价于：

    package com.pixflow.harness.context.config;

    import com.pixflow.harness.context.budget.ConservativeTokenEstimator;
    import com.pixflow.harness.context.budget.ContextBudgetConfig;
    import com.pixflow.harness.context.budget.ContextBudgetService;
    import com.pixflow.harness.context.budget.TokenEstimator;
    import com.pixflow.harness.context.budget.ToolResultExternalizer;
    import com.pixflow.harness.context.compaction.CompactionConfig;
    import com.pixflow.harness.context.compaction.ContextCompactionService;
    import com.pixflow.harness.context.compaction.SummarizationPort;
    import org.springframework.beans.factory.ObjectProvider;
    import org.springframework.boot.autoconfigure.AutoConfiguration;
    import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
    import org.springframework.context.annotation.Bean;

    @AutoConfiguration
    public class ContextAutoConfiguration {

        @Bean
        @ConditionalOnMissingBean(TokenEstimator.class)
        public TokenEstimator tokenEstimator() {
            return new ConservativeTokenEstimator();
        }

        @Bean
        @ConditionalOnMissingBean(ContextBudgetService.class)
        public ContextBudgetService contextBudgetService(
                TokenEstimator tokenEstimator,
                ObjectProvider<ToolResultExternalizer> externalizerProvider) {
            return new ContextBudgetService(
                    ContextBudgetConfig.defaults(),
                    tokenEstimator,
                    externalizerProvider.getIfAvailable());
        }

        @Bean
        @ConditionalOnMissingBean(ContextCompactionService.class)
        public ContextCompactionService contextCompactionService(
                ContextBudgetService budgetService,
                TokenEstimator tokenEstimator,
                ObjectProvider<SummarizationPort> summarizationPortProvider) {
            return new ContextCompactionService(
                    budgetService,
                    tokenEstimator,
                    summarizationPortProvider.getIfAvailable(),
                    CompactionConfig.defaults());
        }
    }

新增 imports 文件：

    pixflow-context/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

文件内容：

    com.pixflow.harness.context.config.ContextAutoConfiguration

新增 `ContextAutoConfigurationTest`。测试用例名称建议为 `providesDefaultContextInfrastructureBeans`、`backsOffWhenCustomTokenEstimatorExists`、`allowsMissingSummarizationPort`。使用 `ApplicationContextRunner` 的配置示例：

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ContextAutoConfiguration.class));

如果测试编译缺少 `ApplicationContextRunner`，不要新增生产依赖；确认 `spring-boot-starter-test` 已在 `pixflow-context/pom.xml` 的 test scope 中存在。

然后运行：

    mvn -pl pixflow-context test

预期输出包含 `BUILD SUCCESS`。如果失败于 import 顺序或缺依赖，先修 context 模块，不要改 app 扫描范围。

接着调整 agent 自动配置。如果决定声明顺序，修改 `pixflow-agent/src/main/java/com/pixflow/agent/config/AgentAutoConfiguration.java`：

    @AutoConfiguration(after = ContextAutoConfiguration.class)

并添加对应 import。然后运行：

    mvn -pl pixflow-agent -am test

如果 agent 测试因为数据库、Redis 或外部服务环境失败，至少运行：

    mvn -pl pixflow-agent -am -DskipTests compile

并记录失败原因。不要把外部服务连接失败误判为本计划失败；本计划关注 Spring 装配缺口。

清查同类问题时运行：

    rg -n "@Component|@Service|@Repository|@Bean\\(" pixflow-*\\src\\main\\java
    rg -n "class .*AutoConfiguration|@AutoConfiguration" pixflow-*\\src\\main\\java
    rg --files | rg "AutoConfiguration\\.imports$"

在 Windows PowerShell 中，如果通配路径报错，可以改为在仓库根目录直接运行 `rg -n "@Component|@Service|@Repository|@Bean\\(" -S`，再按模块筛选。

最后做 app 级验证：

    mvn -pl pixflow-app -am -DskipTests compile

如果本机有 dev profile 需要的 MySQL、Redis、MinIO、RocketMQ 等外部服务，可以继续运行：

    mvn -pl pixflow-app spring-boot:run "-Dspring-boot.run.profiles=dev"

可接受的结果是应用成功启动，或失败推进到真实外部依赖连接错误，例如 Redis/MySQL 连接失败。不可接受的结果是仍报 `No qualifying bean of type 'com.pixflow.harness.context.budget.TokenEstimator'`、`ContextBudgetService` 或 `ContextCompactionService`。

## Validation and Acceptance

本计划的最低验收是：

运行 `mvn -pl pixflow-context test`，新测试 `ContextAutoConfigurationTest` 通过，证明 context 默认发布 `TokenEstimator`、`ContextBudgetService`、`ContextCompactionService`，且 `@ConditionalOnMissingBean` 不覆盖自定义实现。

运行 `mvn -pl pixflow-agent -am test` 或至少 `mvn -pl pixflow-agent -am -DskipTests compile`，证明 agent 能在存在 context 自动配置类后编译，并且没有把 context 默认 Bean 移到 agent 中。

运行 `mvn -pl pixflow-app -am -DskipTests compile`，证明 app 聚合编译通过。若执行 `spring-boot:run`，验收标准是启动日志不再出现：

    No qualifying bean of type 'com.pixflow.harness.context.budget.TokenEstimator'

也不应出现同组后续错误：

    No qualifying bean of type 'com.pixflow.harness.context.budget.ContextBudgetService'
    No qualifying bean of type 'com.pixflow.harness.context.compaction.ContextCompactionService'

同类问题清查的验收是：新增或更新至少一个自动配置测试，能证明本次修复不是只为当前报错硬编码。若清查发现其他启动路径缺口，应在本计划 `Surprises & Discoveries` 增加证据，并在 `Progress` 中增加对应修复项。如果清查没有发现必须立即修的缺口，也要记录使用的命令和结论。

## Idempotence and Recovery

本计划的改动是增量式的，可重复执行。重复创建 `AutoConfiguration.imports` 时，保持文件只有目标自动配置类全限定名，每行一个。重复运行测试不会修改数据库或外部服务。

如果新增 `spring-boot-autoconfigure` 后 context 编译失败，先确认依赖放在 `pixflow-context/pom.xml` 的生产依赖中，而不是 test scope。不要添加 `spring-boot-starter-web` 或其他 starter。context 是底层模块，应只依赖自动配置注解所需的最小库。

如果 `ContextCompactionService` 因 `SummarizationPort` 缺失而行为受限，这是允许的。`SummarizationPort` 是 agent 提供的可选 LLM 摘要端口；context 默认服务必须允许该端口缺失，以便 context 模块独立测试。真正需要 destructive compaction 时，如果没有 summarization port，服务应按现有逻辑降级或返回明确错误。

如果 app 启动暴露新的缺 Bean，不要恢复根包扫描。按本计划的同类问题定义定位该 Bean 的属主模块，在属主模块自动配置中发布默认 Bean 或把依赖改为明确可选，然后补测试。

如果需要回退本计划，删除新增的 `ContextAutoConfiguration.java`、context imports 文件、context 自动配置测试，并从 `pixflow-context/pom.xml` 移除 `spring-boot-autoconfigure`。不要删除 unrelated 工作树改动。回退后当前 `TokenEstimator` 启动错误会复现，这是预期。

## Artifacts and Notes

当前已知错误日志关键段如下：

    UnsatisfiedDependencyException: Error creating bean with name 'agentOrchestrator'
    Unsatisfied dependency expressed through constructor parameter 16:
    No qualifying bean of type 'com.pixflow.harness.context.budget.TokenEstimator' available

当前代码证据如下：

    pixflow-agent/src/main/java/com/pixflow/agent/config/AgentAutoConfiguration.java
    @ComponentScan(basePackages = "com.pixflow.agent")

    pixflow-agent/src/main/java/com/pixflow/agent/AgentOrchestrator.java
    public AgentOrchestrator(...,
                             TokenEstimator tokenEstimator,
                             ContextBudgetService contextBudgetService,
                             ContextCompactionService contextCompactionService,
                             ObjectProvider<SummarizationPort> summarizationPortProvider)

    pixflow-context/src/main/java/com/pixflow/harness/context/budget/ConservativeTokenEstimator.java
    public final class ConservativeTokenEstimator implements TokenEstimator

本计划完成后，应能在 `rg --files | rg "pixflow-context.*AutoConfiguration\\.imports$"` 中看到 context imports 文件，应能在 `rg -n "class ContextAutoConfiguration|TokenEstimator tokenEstimator|ContextBudgetService contextBudgetService|ContextCompactionService contextCompactionService" pixflow-context/src/main` 中看到三个默认 Bean 方法。

## Interfaces and Dependencies

本计划新增或确认以下接口和依赖关系。

`pixflow-context` 新增生产依赖：

    org.springframework.boot:spring-boot-autoconfigure

新增配置类：

    pixflow-context/src/main/java/com/pixflow/harness/context/config/ContextAutoConfiguration.java

该类必须发布：

    TokenEstimator tokenEstimator()
    ContextBudgetService contextBudgetService(TokenEstimator, ObjectProvider<ToolResultExternalizer>)
    ContextCompactionService contextCompactionService(ContextBudgetService, TokenEstimator, ObjectProvider<SummarizationPort>)

这些 Bean 都必须使用 `@ConditionalOnMissingBean`。默认 `TokenEstimator` 实现是 `ConservativeTokenEstimator`。默认 `ContextBudgetService` 使用 `ContextBudgetConfig.defaults()`。默认 `ContextCompactionService` 使用 `CompactionConfig.defaults()`。

不得新增 `context -> agent` 的依赖。`SummarizationPort` 接口已经定义在 context 中，agent 可以实现它；context 只能通过 `ObjectProvider<SummarizationPort>` 可选消费该接口，不能引用 `ForkChildSummarizationPort` 或任何 `com.pixflow.agent.*` 类型。

`AgentAutoConfiguration` 可以声明在 `ContextAutoConfiguration` 之后运行，但不得定义 `TokenEstimator`、`ContextBudgetService` 或 `ContextCompactionService` 的默认 Bean。agent 的职责是装配 Agent 回合，不拥有 context 基础件。

## Revision Notes

2026-07-08 / Codex: 新建本 ExecPlan。计划针对 `AgentOrchestrator` 缺少 `TokenEstimator` Bean 的启动失败，提出 context 模块自装配的重构方案，并把同类 Spring Bean 装配缺口清查纳入范围。核心机制是由 `pixflow-context` 通过 Spring Boot `AutoConfiguration.imports` 发布默认 `TokenEstimator`、`ContextBudgetService`、`ContextCompactionService`，保持 app 扫描边界收窄，不把 context 默认实现塞进 agent。

2026-07-08 / Codex: 完成本 ExecPlan 的代码实施、同类缺口清查与验证记录。新增 `ContextAutoConfiguration`、context 自动配置 imports 和 `ContextAutoConfigurationTest`，`AgentAutoConfiguration` 声明在 context 自动配置之后运行；验证 `mvn -pl pixflow-context test`、`mvn -pl pixflow-agent -am test`、`mvn -pl pixflow-app -am -DskipTests compile`、`mvn -pl pixflow-app test` 均 BUILD SUCCESS，并记录 `mvn -pl pixflow-app -am test` 因长集成测试链路超时。

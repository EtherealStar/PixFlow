# 重构 agent subagent 线程池自动配置，彻底消除 subagentPool Bean 重名冲突

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。后续任何执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。

## Purpose / Big Picture

当前 `pixflow-app` 启动时报 `BeanDefinitionOverrideException`，核心现象是同一个 bean 名字 `subagentPool` 被注册了两次。用户可观察到的失败是 Spring Boot 在容器初始化阶段直接退出，后端服务无法启动。

本计划要用重构方式彻底解决这个问题，而不是打开 `spring.main.allow-bean-definition-overriding=true` 或只做临时改名。完成后，`pixflow-agent` 的 subagent 线程池只会通过明确的 auto-configuration 暴露为一个命名清晰的 `ExecutorService` bean；旧的 `SubagentPool` 配置类会被删除，不保留旧代码、不保留双路径。用户可以通过运行 `mvn -pl pixflow-agent -am test` 和 `mvn -pl pixflow-app -am test` 看到测试通过，并通过启动 `pixflow-app` 看到日志中不再出现 `Cannot register bean definition ... subagentPool`。

## Progress

- [x] (2026-07-08 16:05+08:00) 阅读 `PLANS.md`，确认新计划必须自包含、包含 Progress / Surprises & Discoveries / Decision Log / Outcomes & Retrospective，并给出可验证行为。
- [x] (2026-07-08 16:05+08:00) 读取当前活动计划目录 `docs/design-docs/exec-plans/`，确认本计划应作为新的活动 ExecPlan 放在该目录下。
- [x] (2026-07-08 16:10+08:00) 定位当前冲突点：`pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentPool.java` 同时用类名注册配置类 bean `subagentPool`，又用 `@Bean` 方法注册线程池 bean `subagentPool`。
- [x] (2026-07-08 16:20+08:00) 删除旧 `SubagentPool` 配置类，不保留兼容旧路径。
- [x] (2026-07-08 16:20+08:00) 新增 `AgentSubagentAutoConfiguration`，用明确 bean 名 `subagentExecutor` 暴露 subagent 专用 `ExecutorService`。
- [x] (2026-07-08 16:20+08:00) 修改 `SubagentRunner` 为 `@Qualifier("subagentExecutor")` 注入，避免未来存在多个 `ExecutorService` 时靠类型猜测。
- [x] (2026-07-08 16:20+08:00) 更新 auto-configuration imports 与测试，证明没有 `subagentPool` 同名 bean 冲突。
- [x] (2026-07-08 16:20+08:00) 运行 agent 与 app 层验证命令，并把结果写回本计划的 Outcomes & Retrospective。

## Surprises & Discoveries

- Observation: 这次错误不只是 Maven 多模块 classpath 同时出现源码和 jar 的问题，代码本身也存在同名 bean 设计缺陷。
  Evidence: `SubagentPool.java` 中 `@Configuration public class SubagentPool` 的默认 bean 名是 `subagentPool`；同一类内 `@Bean public ExecutorService subagentPool(...)` 的默认 bean 名也是 `subagentPool`。即使清理 classpath，这个命名也很脆弱。

- Observation: 报错中同时出现 `class path resource [com/pixflow/agent/subagent/SubagentPool.class]` 与 `jar:file:/C:/Users/rowla/.m2/repository/.../pixflow-agent-1.0.0-SNAPSHOT.jar`，说明启动环境读到了本地 Maven snapshot jar。
  Evidence: 用户提供的错误日志显示 `defined in URL [jar:file:/C:/Users/rowla/.m2/repository/com/pixflow/pixflow-agent/1.0.0-SNAPSHOT/pixflow-agent-1.0.0-SNAPSHOT.jar!/com/pixflow/agent/subagent/SubagentPool.class]`。这要求修复时同时给出干净启动方式，避免 IDE 或单模块启动继续加载旧 snapshot。

- Observation: `pixflow-agent` 的设计定位是 Spring Boot 可复用模块，由 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 引入自动配置。
  Evidence: `pixflow-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 当前注册 `com.pixflow.agent.config.AgentAutoConfiguration`；`AgentAutoConfiguration` 通过 `@ComponentScan(basePackages = "com.pixflow.agent")` 扫描 agent 组件。

## Decision Log

- Decision: 删除旧 `SubagentPool` 配置类，而不是把类改名为 `SubagentPoolConfiguration` 后继续保留。
  Rationale: 用户明确要求“不要保留旧代码”。保留同一职责的旧类会让后续维护者继续误认为 subagent 线程池配置属于 `subagent` 业务包。删除旧类并把装配移到 `config` 包，可以把“业务组件”和“Spring 装配”边界一次性收敛。
  Date/Author: 2026-07-08 / Codex

- Decision: 新线程池 bean 命名为 `subagentExecutor`，不沿用 `subagentPool`。
  Rationale: `subagentPool` 同时像类名、配置名和资源名，已经造成冲突。`subagentExecutor` 更准确表达 bean 类型是 `ExecutorService`，并且不会与任何配置类名自然冲突。由于当前 `SubagentRunner` 按类型注入 `ExecutorService`，没有稳定依赖旧 bean 名的业务契约；本计划不保留旧 bean 别名。
  Date/Author: 2026-07-08 / Codex

- Decision: 使用 `@Qualifier(AgentSubagentAutoConfiguration.SUBAGENT_EXECUTOR_BEAN)` 明确注入线程池。
  Rationale: app 级服务未来可能加入其他 `ExecutorService`，仅靠类型注入会变成新的隐性风险。使用常量化 qualifier 可以让编译器帮助发现重命名遗漏。
  Date/Author: 2026-07-08 / Codex

- Decision: 不使用 `spring.main.allow-bean-definition-overriding=true`。
  Rationale: 允许覆盖只能掩盖问题，无法保证最终留下的是配置类 bean 还是线程池 bean，且会降低整个应用发现重复装配错误的能力。
  Date/Author: 2026-07-08 / Codex

- Decision: 本计划不做大规模移除 `AgentAutoConfiguration` 的 `@ComponentScan`。
  Rationale: 完全取消 component scan 并显式声明全部 agent bean 是更大的模块装配重构，会影响 prompt、skill、memory、sessionmemory、hooks、planmode 等大量 bean。当前问题的完整修复只需要把容易冲突的基础设施 bean 从业务包配置类迁移到专用 auto-configuration，并删除旧类。后续可以另立计划把 agent 模块整体改成全显式 starter 装配。
  Date/Author: 2026-07-08 / Codex

## Outcomes & Retrospective

本计划已实施完成。实际删除 `pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentPool.java`；新增 `pixflow-agent/src/main/java/com/pixflow/agent/config/AgentSubagentAutoConfiguration.java`；修改 `SubagentRunner` 通过 `AgentSubagentAutoConfiguration.SUBAGENT_EXECUTOR_BEAN` 明确注入 `subagentExecutor`；修改 auto-configuration imports 注册新配置类。

新增测试 `AgentSubagentAutoConfigurationTest` 验证默认上下文只暴露名为 `subagentExecutor` 的 `ExecutorService`，不存在旧 bean，且 `SubagentRunner` 能通过该 executor 装配并运行。`AgentArchitectureTest` 新增约束：`com.pixflow.agent.subagent..` 包内类不得使用 `@Configuration`。

源码检查结果：

    rg -n 'class SubagentPool|subagentPool\(|@Bean\(name = "subagentPool"|"subagentPool"|SubagentPool|subagentPool' pixflow-agent\src\main\java pixflow-agent\src\test\java
    # 无输出

验证命令结果：

    mvn -pl pixflow-agent "-Dtest=AgentSubagentAutoConfigurationTest,AgentArchitectureTest" test
    [INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS

    mvn -pl pixflow-agent test
    [INFO] Tests run: 36, Failures: 0, Errors: 0, Skipped: 0
    [INFO] BUILD SUCCESS

    mvn -pl pixflow-agent -am test
    [INFO] PixFlow Agent ...................................... SUCCESS
    [INFO] BUILD SUCCESS

    mvn -pl pixflow-app -am test
    [INFO] PixFlow Agent ...................................... SUCCESS
    [INFO] PixFlow App ........................................ SUCCESS
    [INFO] BUILD SUCCESS

未运行 `mvn -pl pixflow-app -am spring-boot:run` 常驻启动验证；app reactor 测试已覆盖当前 Maven reactor 装配路径，且本次源码检查和 Spring context 测试均未发现 `subagentPool` bean 或 `BeanDefinitionOverrideException` 风险。未发现需要清理本地 Maven snapshot jar 的额外 classpath 问题。

## Context and Orientation

本仓库是 Java 21 + Spring Boot 3.5.x 的 Maven 多模块项目。仓库根目录是 `D:\study\PixFlow`。`pixflow-app` 是最终启动的 Spring Boot 应用模块，主类是 `pixflow-app/src/main/java/com/pixflow/app/PixFlowApplication.java`。`pixflow-agent` 是 Agent 决策层装配模块，通过 Maven 依赖被 `pixflow-app/pom.xml` 引入。

Spring bean 是 Spring 容器管理的对象。`@Component`、`@Service`、`@Configuration` 等注解会把类注册成 bean；`@Bean` 方法会把方法返回值也注册成 bean。默认 bean 名通常来自类名或方法名的首字母小写。例如类 `SubagentPool` 的默认 bean 名是 `subagentPool`，方法 `subagentPool(...)` 的默认 bean 名也是 `subagentPool`。当 Spring Boot 默认配置 `spring.main.allow-bean-definition-overriding=false` 时，同名 bean 会直接导致启动失败。

当前相关文件如下：

- `pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentPool.java`：旧配置类，当前同时声明 `@Configuration` 和 `@Bean subagentPool(...)`，这是本计划要删除的旧代码。
- `pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentRunner.java`：subagent 异步执行入口，当前持有 `ExecutorService subagentPool`，调用 `CompletableFuture.supplyAsync(..., subagentPool)`。
- `pixflow-agent/src/main/java/com/pixflow/agent/config/AgentAutoConfiguration.java`：agent 模块主自动配置，启用 `AgentProperties`、MyBatis mapper scan，并通过 `@ComponentScan(basePackages = "com.pixflow.agent")` 扫描 agent 组件。
- `pixflow-agent/src/main/java/com/pixflow/agent/config/AgentProperties.java`：集中配置，其中 `pixflow.agent.subagent.pool.core-size`、`max-size`、`queue-capacity`、`keep-alive-seconds` 用于构造 subagent 线程池。
- `pixflow-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`：Spring Boot 自动配置注册文件。每一行是一个 auto-configuration 类的全限定类名。
- `pixflow-app/pom.xml`：app 模块依赖 `pixflow-agent`。从根工程用 `mvn -pl pixflow-app -am ...` 运行时，Maven reactor 会优先使用当前工作区的 `pixflow-agent` 模块。

本计划中的“旧代码”特指 `SubagentPool.java` 这个配置类以及旧 bean 名 `subagentPool`。执行本计划时不得留下 `@Bean(name = "subagentPool")`、不得留下旧类空壳、不得用别名把 `subagentPool` 指回新 bean。

## Plan of Work

第一步，删除旧配置类 `pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentPool.java`。删除后，`subagent` 包只保留业务运行类，例如 `SubagentRunner`、`SubagentRequest`、`SubagentResult`、`ChildToolFilter` 和工具 handler。线程池属于基础设施装配，不再放在这个包内。

第二步，在 `pixflow-agent/src/main/java/com/pixflow/agent/config/` 新建 `AgentSubagentAutoConfiguration.java`。这个类使用 `@AutoConfiguration`，提供一个常量 `public static final String SUBAGENT_EXECUTOR_BEAN = "subagentExecutor"`，并用 `@Bean(name = SUBAGENT_EXECUTOR_BEAN, destroyMethod = "shutdown")` 暴露 `ExecutorService`。构造逻辑应沿用旧 `SubagentPool` 的线程池参数：核心线程数、最大线程数、队列容量和 keep-alive 秒数都来自 `AgentProperties.Subagent.Pool`。线程名仍使用 `agent-subagent-1`、`agent-subagent-2` 这种格式，便于日志排查。

第三步，给新 bean 加 `@ConditionalOnMissingBean(name = SUBAGENT_EXECUTOR_BEAN)`。这不是保留旧路径，而是 Spring Boot starter 的常规扩展点：测试或部署环境可以用同名 bean 替换默认线程池。注意只允许同名替换，不提供 `subagentPool` 旧名字。

第四步，修改 `pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentRunner.java` 的构造函数。它应通过 `@Qualifier(AgentSubagentAutoConfiguration.SUBAGENT_EXECUTOR_BEAN)` 注入 `ExecutorService`，字段名从 `subagentPool` 改为 `subagentExecutor`。日志文案也改为 `SubagentRunner initialized with executor: ...`。这一步让未来多个 executor 共存时不会注入错对象。

第五步，修改 `pixflow-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，加入新自动配置类 `com.pixflow.agent.config.AgentSubagentAutoConfiguration`。保留现有 `AgentAutoConfiguration`。顺序建议先 `AgentAutoConfiguration`，后 `AgentSubagentAutoConfiguration`；两者都依赖 `AgentProperties`，而 `AgentAutoConfiguration` 已启用 `@EnableConfigurationProperties(AgentProperties.class)`。如果后续发现单独导入 `AgentSubagentAutoConfiguration` 时缺少 properties 绑定，可以在新类上也加 `@EnableConfigurationProperties(AgentProperties.class)`，这在 Spring Boot 中是幂等的。

第六步，新增或修改测试。优先新增 `pixflow-agent/src/test/java/com/pixflow/agent/config/AgentSubagentAutoConfigurationTest.java`，使用 `ApplicationContextRunner` 或轻量 Spring context 验证三件事：默认上下文有且只有一个名为 `subagentExecutor` 的 `ExecutorService`；上下文中没有名为 `subagentPool` 的 bean；`SubagentRunner` 可以注入并调用该 executor。测试结束时应关闭 executor，避免测试进程悬挂。

第七步，更新架构测试。如果 `pixflow-agent/src/test/java/com/pixflow/agent/architecture/AgentArchitectureTest.java` 已有 Spring 装配边界约束，就新增规则：`com.pixflow.agent.subagent..` 包内类不应使用 `@Configuration` 注解。这样未来不会再次把基础设施配置类放回业务包。

第八步，运行验证命令。先跑 `mvn -pl pixflow-agent -am test` 验证 agent 模块，再跑 `mvn -pl pixflow-app -am test` 验证 app 装配。若需要本地启动验证，使用根目录命令 `mvn -pl pixflow-app -am spring-boot:run`，不要从 `pixflow-app` 子目录单独启动旧 snapshot。启动失败如果仍出现 m2 jar 路径，应清理或重装本地 snapshot：运行 `mvn -pl pixflow-agent -am clean install` 后重新导入 IDE Maven 项目。

## Concrete Steps

所有命令都在仓库根目录 `D:\study\PixFlow` 执行。

先确认旧冲突仍存在：

    rg -n "class SubagentPool|subagentPool\\(|@Configuration" pixflow-agent/src/main/java/com/pixflow/agent/subagent

预期当前能看到：

    pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentPool.java:27:@Configuration
    pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentPool.java:28:public class SubagentPool {
    pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentPool.java:34:public ExecutorService subagentPool(AgentProperties props) {

删除旧文件：

    删除 pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentPool.java

新增 `pixflow-agent/src/main/java/com/pixflow/agent/config/AgentSubagentAutoConfiguration.java`。文件应包含以下结构，具体 import 由 IDE 或编译器提示补齐：

    package com.pixflow.agent.config;

    @AutoConfiguration
    public class AgentSubagentAutoConfiguration {
        public static final String SUBAGENT_EXECUTOR_BEAN = "subagentExecutor";

        @Bean(name = SUBAGENT_EXECUTOR_BEAN, destroyMethod = "shutdown")
        @ConditionalOnMissingBean(name = SUBAGENT_EXECUTOR_BEAN)
        public ExecutorService subagentExecutor(AgentProperties props) {
            AgentProperties.Subagent.Pool poolCfg = props.getSubagent().getPool();
            return new ThreadPoolExecutor(
                    poolCfg.getCoreSize(),
                    poolCfg.getMaxSize(),
                    poolCfg.getKeepAliveSeconds(),
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(poolCfg.getQueueCapacity()),
                    new SubagentThreadFactory());
        }

        private static final class SubagentThreadFactory implements ThreadFactory {
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "agent-subagent-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        }
    }

修改 `pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentRunner.java`。构造函数应变成：

    public SubagentRunner(
            @Qualifier(AgentSubagentAutoConfiguration.SUBAGENT_EXECUTOR_BEAN)
            ExecutorService subagentExecutor) {
        this.subagentExecutor = subagentExecutor;
        log.info("SubagentRunner initialized with executor: {}",
                subagentExecutor.getClass().getSimpleName());
    }

字段和调用点也改名：

    private final ExecutorService subagentExecutor;

    return CompletableFuture.supplyAsync(() -> runSync(req), subagentExecutor);

修改自动配置 imports 文件 `pixflow-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，确保至少包含：

    com.pixflow.agent.config.AgentAutoConfiguration
    com.pixflow.agent.config.AgentSubagentAutoConfiguration

删除旧引用后检查：

    rg -n "class SubagentPool|subagentPool\\(|@Bean\\(name = \"subagentPool\"|\"subagentPool\"" pixflow-agent/src/main/java pixflow-agent/src/test/java

预期输出为空。若命中 `SubagentRunner` 的旧字段名、日志或测试断言，应继续改到 `subagentExecutor`。

运行测试：

    mvn -pl pixflow-agent -am test

成功时应看到 Maven 末尾包含：

    [INFO] PixFlow Agent ...................................... SUCCESS
    [INFO] BUILD SUCCESS

再运行：

    mvn -pl pixflow-app -am test

成功时应看到：

    [INFO] PixFlow App ........................................ SUCCESS
    [INFO] BUILD SUCCESS

如果要验证启动行为，运行：

    mvn -pl pixflow-app -am spring-boot:run

成功标准不是必须连接所有外部服务，而是启动日志不再出现以下 bean 覆盖错误：

    BeanDefinitionOverrideException: Invalid bean definition with name 'subagentPool'
    Cannot register bean definition ... for bean 'subagentPool'

## Validation and Acceptance

验收分为源码结构、单测、app 装配和启动日志四层。

源码结构验收：`pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentPool.java` 必须不存在。`rg -n "subagentPool" pixflow-agent/src/main/java pixflow-agent/src/test/java` 不应命中旧 bean 名。允许命中新计划文档或历史文档，不允许命中生产 Java 代码。`AgentSubagentAutoConfiguration` 必须是唯一创建 subagent executor 的位置。

单测验收：`mvn -pl pixflow-agent -am test` 必须 BUILD SUCCESS。新增测试应证明 `subagentExecutor` bean 存在，`subagentPool` bean 不存在，`SubagentRunner` 可以被 Spring 装配。这个测试在删除旧代码前应该失败或无法表达，因为旧上下文里存在同名冲突风险；重构后应稳定通过。

app 装配验收：`mvn -pl pixflow-app -am test` 必须 BUILD SUCCESS。这证明 `pixflow-app` 通过 Maven reactor 引入当前工作区的 `pixflow-agent` 后，不会在自动配置阶段因 subagent executor 装配失败。

启动日志验收：运行 `mvn -pl pixflow-app -am spring-boot:run` 时，不应再出现 `BeanDefinitionOverrideException` 和 `subagentPool`。如果启动因 MySQL、Redis、RocketMQ、Qdrant、MinIO 等外部依赖不可用而失败，应确认失败栈与 `subagentPool` 无关；外部服务失败不算本计划失败。

## Idempotence and Recovery

本计划的代码修改是幂等的。重复删除 `SubagentPool.java` 时，如果文件已经不存在，继续执行后续检查即可。重复添加 `AgentSubagentAutoConfiguration` 时，应以最终文件内容为准，不要新增第二个类似配置类。

如果编译失败提示找不到 `AgentSubagentAutoConfiguration`，检查 `SubagentRunner` import 是否正确，包名是否是 `com.pixflow.agent.config`。如果 Spring context 测试提示缺少 `AgentProperties`，在 `AgentSubagentAutoConfiguration` 上添加 `@EnableConfigurationProperties(AgentProperties.class)`；这不会破坏已有 `AgentAutoConfiguration`。

如果运行 `mvn -pl pixflow-app -am ...` 仍看到旧 jar 中的 `SubagentPool.class`，说明启动环境没有使用当前 reactor 或本地 m2 snapshot 仍是旧产物。先运行 `mvn -pl pixflow-agent -am clean install`，再重新导入 IDE Maven 项目。不要手动删除用户的本地 Maven 仓库目录，除非用户明确要求。

如果测试新增了 executor 后 JVM 无法退出，说明测试上下文没有关闭线程池。应确保测试使用 Spring context 的关闭流程，或在测试结束后关闭拿到的 `ExecutorService`。生产 bean 已通过 `destroyMethod = "shutdown"` 管理生命周期。

## Artifacts and Notes

当前错误日志的关键片段如下：

    BeanDefinitionOverrideException: Invalid bean definition with name 'subagentPool'
    Cannot register bean definition ... factoryMethodName=subagentPool ...
    since there is already ... class=com.pixflow.agent.subagent.SubagentPool ...

旧代码的冲突形态如下：

    @Configuration
    public class SubagentPool {
        @Bean(destroyMethod = "shutdown")
        public ExecutorService subagentPool(AgentProperties props) {
            ...
        }
    }

新代码的目标形态如下：

    @AutoConfiguration
    public class AgentSubagentAutoConfiguration {
        public static final String SUBAGENT_EXECUTOR_BEAN = "subagentExecutor";

        @Bean(name = SUBAGENT_EXECUTOR_BEAN, destroyMethod = "shutdown")
        @ConditionalOnMissingBean(name = SUBAGENT_EXECUTOR_BEAN)
        public ExecutorService subagentExecutor(AgentProperties props) {
            ...
        }
    }

这不是兼容性迁移。本计划不提供 `subagentPool` 旧 bean 别名，也不保留 `SubagentPool` 空类。任何依赖旧名字的代码都必须改为使用 `AgentSubagentAutoConfiguration.SUBAGENT_EXECUTOR_BEAN`。

## Interfaces and Dependencies

实施完成后，`pixflow-agent` 应有以下接口和依赖关系。

`pixflow-agent/src/main/java/com/pixflow/agent/config/AgentSubagentAutoConfiguration.java` 定义：

    package com.pixflow.agent.config;

    public class AgentSubagentAutoConfiguration {
        public static final String SUBAGENT_EXECUTOR_BEAN = "subagentExecutor";

        public ExecutorService subagentExecutor(AgentProperties props);
    }

`pixflow-agent/src/main/java/com/pixflow/agent/subagent/SubagentRunner.java` 构造函数使用：

    public SubagentRunner(
            @Qualifier(AgentSubagentAutoConfiguration.SUBAGENT_EXECUTOR_BEAN)
            ExecutorService subagentExecutor)

`pixflow-agent/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 包含：

    com.pixflow.agent.config.AgentAutoConfiguration
    com.pixflow.agent.config.AgentSubagentAutoConfiguration

不应再存在以下生产类型或 bean 名：

    com.pixflow.agent.subagent.SubagentPool
    bean name: subagentPool

本计划继续依赖 Spring Boot autoconfigure、Spring context、Java `ExecutorService`、`ThreadPoolExecutor`、`ArrayBlockingQueue`、`ThreadFactory` 和 `AtomicInteger`。不得新增第三方线程池库。

## Revision Notes

2026-07-08 / Codex: 新建本 ExecPlan。原因是 `SubagentPool` 的 `@Configuration` 类名和 `@Bean subagentPool(...)` 方法名派生出同一个 bean 名，导致 Spring Boot 默认禁止 bean 覆盖时启动失败。用户要求撰写中文计划并说明彻底解决机制，且不要保留旧代码，因此本计划选择删除旧配置类，新增明确的 `AgentSubagentAutoConfiguration` 和 `subagentExecutor` bean，并通过测试和启动日志验证问题消失。

2026-07-08 / Codex: 完成本 ExecPlan 的代码实施与验证。删除旧 `SubagentPool`，新增 `AgentSubagentAutoConfiguration`，`SubagentRunner` 改为 qualifier 注入 `subagentExecutor`，auto-configuration imports 注册新配置类，补充 Spring context 测试与 ArchUnit 约束；`mvn -pl pixflow-agent -am test` 和 `mvn -pl pixflow-app -am test` 均 BUILD SUCCESS。

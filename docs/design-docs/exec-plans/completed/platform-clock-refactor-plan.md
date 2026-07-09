# 统一平台 Clock，彻底消除模块私有时间源装配歧义

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`、`AGENTS.md`、`docs/design-docs/index.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/design-docs/exec-plans/completed/spring-autoconfiguration-boundary-refactor-plan.md` 与 `docs/design-docs/base/common.md`。后续执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。

## Purpose / Big Picture

当前 PixFlow 启动多次卡在 `java.time.Clock` 注入歧义上。最近一次日志显示 `AssetPackageService` 需要一个 `Clock`，但 Spring 容器里同时有 `permissionClock`、`authClock`、`commerceClock`、`conversationClock`、`dagClock`、`imagegenClock`、`rubricsClock`、`taskClock` 等多个同类型 Bean，导致应用上下文启动失败。给某一个参数补 `@Qualifier("fileClock")` 只能修复当前报错，后续任何模块新增裸 `Clock` 参数都可能再次失败。

完成本重构后，整个应用运行时只有一个平台级 `Clock` Bean，所有模块都通过普通 `Clock clock` 注入同一个时间源。模块自动配置不再声明 `permissionClock`、`taskClock`、`fileClock` 这类私有时间源，也不再使用 `@Qualifier("xxxClock")`。用户可观察到的结果是：`pixflow-app` 不再因为多个 `Clock` Bean 报 `expected single matching bean but found ...`；测试需要固定时间时，只覆盖唯一的 `Clock` Bean 即可控制全应用时间。

## Progress

- [x] (2026-07-05 17:05+08:00) 已读取 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、当前 active execution plans、`docs/design-docs/base/common.md` 与已完成的 Spring 自动配置边界重构计划。
- [x] (2026-07-05 17:10+08:00) 已定位最近一次启动失败根因：`FileAutoConfiguration#fileClock` 使用按类型的 `@ConditionalOnMissingBean`，在已有其他模块 `Clock` Bean 时不会创建；`assetPackageService(... Clock clock)` 又没有限定名称，最终被 8 个候选 Bean 卡住。
- [x] (2026-07-05 17:15+08:00) 已确认结构性问题不只在 file 模块。当前主代码中多个自动配置类声明模块私有 `Clock` Bean，并在 commerce、dag、imagegen、rubrics、auth 等模块中使用 `@Qualifier("xxxClock")`。
- [x] (2026-07-05 17:20+08:00) 已决定本计划采用完整有效重构：新增唯一平台级 `Clock`，删除模块私有 `Clock` Bean 和相关 qualifier，不保留无用旧代码或兼容层。
- [x] (2026-07-05 17:03+08:00) 已新增 `pixflow-common/src/main/java/com/pixflow/common/time/TimeAutoConfiguration.java`、common 自动配置 imports，并在 `pixflow-common/pom.xml` 增加 `spring-boot-autoconfigure`。
- [x] (2026-07-05 17:06+08:00) 已删除主代码中的模块私有 `permissionClock`、`authClock`、`commerceClock`、`conversationClock`、`dagClock`、`fileClock`、`imagegenClock`、`memoryClock`、`rubricsClock`、`stateClock`、`taskClock` Bean 方法。
- [x] (2026-07-05 17:06+08:00) 已删除所有主代码中的 `@Qualifier("xxxClock") Clock` 参数，并把 app 层 `TaskQueryConfiguration`、`CustomDownloadConfiguration` 的 `taskClock` 参数改为普通 `clock`。
- [x] (2026-07-05 17:06+08:00) 已修正 commerce、file、state、imagegen 等 Spring 上下文测试，使它们通过 `TimeAutoConfiguration` 获得唯一 `Clock`；纯 Java 单元测试继续直接传 `Clock.fixed(...)`。
- [x] (2026-07-05 17:16+08:00) 已新增 `TimeAutoConfigurationTest` 和 `ClockBeanBoundaryTest`，守护默认平台时间源、用户覆盖机制，以及主代码不再引入模块私有 Clock Bean / clock qualifier。
- [x] (2026-07-05 17:20+08:00) 已运行验证并记录结果；`pixflow-common`、commerce、file、state、auth、memory、app 测试通过，task/dag/rubrics 主代码编译通过，`pixflow-app -am -DskipTests install` 通过。

## Surprises & Discoveries

- Observation: file 模块这次报错的直接触发点不是缺少 `Clock`，而是存在太多 `Clock`。
  Evidence: 启动日志显示 `No qualifying bean of type 'java.time.Clock' available: expected single matching bean but found 8: permissionClock,authClock,commerceClock,conversationClock,dagClock,imagegenClock,rubricsClock,taskClock`。

- Observation: `FileAutoConfiguration#fileClock` 当前并不会稳定创建 file 自己的时间源。
  Evidence: 该方法使用 `@ConditionalOnMissingBean`，这是按类型判断。只要容器中已经有任意 `Clock`，例如 `taskClock` 或 `commerceClock`，`fileClock` 就会被跳过。

- Observation: 模块私有 `Clock` 模式已经扩散，不适合继续通过补 qualifier 修补。
  Evidence: `rg -n "Clock|@Qualifier\\(\".*Clock\"\\)|@ConditionalOnMissingBean\\(name = \".*Clock\"\\)" pixflow-*\\src\\main\\java` 可定位到 `PermissionAutoConfiguration`、`AuthAutoConfiguration`、`CommerceAutoConfiguration`、`ConversationAutoConfiguration`、`DagAutoConfiguration`、`ImagegenAutoConfiguration`、`RubricsAutoConfiguration`、`TaskAutoConfiguration`、`FileAutoConfiguration`、`StateAutoConfiguration` 等多处声明或消费。

- Observation: `pixflow-common` 已经承载 Spring Web / Spring Context 级横切能力，例如 `HttpErrorRenderer` 与 `ProgressNotifier` SPI，因此把平台时间源自动配置放在 common 不会引入一个全新的层级概念。
  Evidence: `pixflow-common/pom.xml` 当前已有 `spring-web`、`spring-context`、`jakarta.servlet-api` 和 Jackson annotations 依赖；`docs/design-docs/base/common.md` 明确 common 是 Wave 0 地基，承载无业务知识的横切能力。

- Observation: 实施时发现 `pixflow-module-memory` 也声明了 `memoryClock()` 私有 Bean，最初目标文件清单未显式列出它。
  Evidence: `rg -n "public Clock" .` 在实施前命中 `pixflow-module-memory/src/main/java/com/pixflow/module/memory/config/MemoryAutoConfiguration.java:69`。该 Bean 同样会导致运行时多 `Clock` 候选，因此已一并删除。

- Observation: Windows PowerShell 下 `rg pixflow-*\\src\\main\\java` 这类路径通配不会按预期展开，部分复杂 `rg` 命令还触发过沙箱进程启动错误。
  Evidence: 工具输出显示 `rg: pixflow-*\\src\\main\\java: IO error ... 文件名、目录名或卷标语法不正确`，后续改为从仓库根使用 `rg -g "pixflow-*/src/main/java/**/*.java"` 或使用 app 守护测试完成扫描。

- Observation: 受影响模块全量 `-am test` 在 `pixflow-infra-image` 的既有测试编译问题处失败，未推进到 dag/task/rubrics 测试；该失败与 Clock 重构无关。
  Evidence: `mvn -pl pixflow-module-dag -am test` 与 `mvn -pl pixflow-module-task -am test` 均失败在 `pixflow-infra-image` 的 `DefaultImageCodecTest`，错误为 `The import com.pixflow.infra.image.config cannot be resolved`、`DefaultImageCodec cannot be resolved to a type` 等。对应主代码验证 `mvn -pl pixflow-module-dag -am -DskipTests compile`、`mvn -pl pixflow-module-task -am -DskipTests compile` 和 `mvn -pl pixflow-module-rubrics -am -DskipTests compile` 均通过。

## Decision Log

- Decision: 本次重构只保留一个平台级 `Clock` Bean，不保留模块私有 `permissionClock`、`authClock`、`commerceClock`、`conversationClock`、`dagClock`、`fileClock`、`imagegenClock`、`rubricsClock`、`stateClock`、`taskClock`。
  Rationale: 模块私有时间源没有表达真实业务差异，反而制造同类型 Bean 歧义。用户明确要求不为了迁移式安全保留没用旧代码，因此本计划采用一次性完整替换。
  Date/Author: 2026-07-05 / Codex

- Decision: 平台时间源放在 `pixflow-common`，新增 `com.pixflow.common.time.TimeAutoConfiguration`。
  Rationale: `common` 是所有 infra、harness、module、agent 依赖的 Wave 0 横切地基，时间源是无业务知识的横切基础能力。把它放在 common 能避免新增模块和 Maven 依赖边，也符合 Spring 自动配置边界重构后的“可复用能力通过 AutoConfiguration.imports 进入容器”模型。
  Date/Author: 2026-07-05 / Codex

- Decision: `TimeAutoConfiguration` 使用 `@ConditionalOnMissingBean(Clock.class)` 创建名为 `pixflowClock` 的 `Clock.systemUTC()`，不使用 `@Primary` 作为长期机制。
  Rationale: 完整重构后运行时应只有一个 `Clock`，不需要靠 `@Primary` 在多个候选中裁决。`@ConditionalOnMissingBean(Clock.class)` 允许测试或 app 显式提供一个固定 `Clock` 来覆盖默认时间源。
  Date/Author: 2026-07-05 / Codex

- Decision: 所有自动配置方法统一使用裸 `Clock clock` 参数，不再使用 `@Qualifier("xxxClock") Clock`。
  Rationale: 当容器中只有一个 `Clock` 时，裸注入是最清晰的契约。保留 qualifier 会继续暗示存在多个模块时间源，违背本次重构目标。
  Date/Author: 2026-07-05 / Codex

- Decision: 纯 Java 单元测试仍可直接构造 `Clock.fixed(...)` 传给被测对象；Spring 上下文测试则通过覆盖唯一 `Clock` Bean 控制时间。
  Rationale: 纯单元测试不经过 Spring 容器，直接传构造参数更轻。只有 Spring 装配测试需要验证平台 Bean 行为，应该使用唯一 `Clock` 覆盖机制。
  Date/Author: 2026-07-05 / Codex

## Outcomes & Retrospective

已完成统一平台 Clock 重构。`pixflow-common` 现在通过 `TimeAutoConfiguration#pixflowClock()` 提供唯一默认 `Clock.systemUTC()`，并通过 `AutoConfiguration.imports` 自动导入；测试或应用若需要固定时间，可以提供唯一 `Clock` Bean 覆盖默认值。

已删除的模块私有时间源包括 `permissionClock`、`authClock`、`commerceClock`、`conversationClock`、`dagClock`、`fileClock`、`imagegenClock`、`memoryClock`、`rubricsClock`、`stateClock`、`taskClock`。所有主代码中的 `@Qualifier("xxxClock") Clock` 已移除，自动配置和 app 配置统一裸注入 `Clock clock`。主代码中 `public Clock` 静态检查只剩 `pixflow-common/src/main/java/com/pixflow/common/time/TimeAutoConfiguration.java`。

新增守护测试 `pixflow-common/src/test/java/com/pixflow/common/time/TimeAutoConfigurationTest.java` 覆盖默认平台 Clock 和用户覆盖机制；新增 `pixflow-app/src/test/java/com/pixflow/app/ClockBeanBoundaryTest.java` 扫描主源码，防止恢复模块私有 Clock Bean、Clock qualifier 或按名称判断的 Clock 条件装配。

验证结果：`mvn -pl pixflow-common test`、`mvn -pl pixflow-module-commerce -am test`、`mvn -pl pixflow-module-file -am test`、`mvn -pl pixflow-state -am test`、`mvn -pl pixflow-infra-auth -am test`、`mvn -pl pixflow-module-memory -am test`、`mvn -pl pixflow-app test` 均通过；`mvn -pl pixflow-module-dag -am -DskipTests compile`、`mvn -pl pixflow-module-task -am -DskipTests compile`、`mvn -pl pixflow-module-rubrics -am -DskipTests compile` 均通过；`mvn -pl pixflow-app -am -DskipTests install` 通过。`mvn -pl pixflow-module-dag -am test` 和 `mvn -pl pixflow-module-task -am test` 当前被上游 `pixflow-infra-image` 既有测试编译问题阻断，失败点不是 Clock 候选 Bean 歧义。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。项目是 Spring Boot 3.5 多模块 Maven 工程。Spring Bean 是由 Spring 容器管理的对象；自动配置是 Spring Boot 从各模块 jar 的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 文件读取配置类，再由配置类创建 Bean 的机制。当前项目已经完成一轮自动配置边界重构，主应用 `pixflow-app` 只扫描 `com.pixflow.app`，可复用模块通过自动配置进入容器。

`java.time.Clock` 是 JDK 的时间源抽象。业务代码不应直接调用 `Instant.now()` 或 `LocalDateTime.now()`，而应注入 `Clock` 后调用 `Instant.now(clock)`，这样测试可以用 `Clock.fixed(...)` 固定当前时间。这个设计是正确的；错误在于当前每个模块各自声明了一个不同名称的 `Clock` Bean。

最近一次启动失败发生在 file 模块。`pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileAutoConfiguration.java` 中声明了 `fileClock()`，但它使用按类型判断的 `@ConditionalOnMissingBean`。当其他模块已经创建 `permissionClock`、`taskClock` 等任意 `Clock` 后，`fileClock()` 不会创建。随后同一配置类的 `assetPackageService(... Clock clock)` 参数需要注入一个 `Clock`，Spring 发现多个候选，无法决定使用哪个，于是启动失败。

这不是 file 模块独有问题。当前主代码中可看到多个模块自动配置声明私有时间源，例如 `PermissionAutoConfiguration#permissionClock`、`AuthAutoConfiguration#authClock`、`CommerceAutoConfiguration#commerceClock`、`ConversationAutoConfiguration#conversationClock`、`DagAutoConfiguration#dagClock`、`ImagegenAutoConfiguration#imagegenClock`、`RubricsAutoConfiguration#rubricsClock`、`TaskAutoConfiguration#taskClock`、`FileAutoConfiguration#fileClock`、`StateAutoConfiguration#stateClock`。部分模块还用 `@Qualifier("commerceClock")`、`@Qualifier("dagClock")` 等显式选择这些私有 Bean。

本计划的最终状态很简单：Spring 容器中只有一个 `Clock` Bean，名字是 `pixflowClock`，类型是 `java.time.Clock`，默认值是 UTC 系统时间。所有模块只依赖 `Clock` 类型，不关心 Bean 名称。

## Plan of Work

第一步是在 `pixflow-common` 中新增平台时间源自动配置。新增文件 `pixflow-common/src/main/java/com/pixflow/common/time/TimeAutoConfiguration.java`，内容是一个 `@AutoConfiguration` 类，声明 `@Bean` 方法 `pixflowClock()`。该方法使用 `@ConditionalOnMissingBean(Clock.class)`，返回 `Clock.systemUTC()`。同时在 `pixflow-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中登记 `com.pixflow.common.time.TimeAutoConfiguration`。如果 `pixflow-common/src/main/resources` 目录不存在，则创建该资源路径。因为 `pixflow-common` 当前没有 `spring-boot-autoconfigure` 主依赖，还需要在 `pixflow-common/pom.xml` 中新增 `org.springframework.boot:spring-boot-autoconfigure`，用于编译 `@AutoConfiguration` 与 `@ConditionalOnMissingBean`。

第二步是删除所有模块私有 `Clock` Bean 方法。需要编辑每个声明了 `*Clock()` 的自动配置类，删除方法本身，并删除不再需要的 `@ConditionalOnMissingBean(name = "...Clock")` import。目标文件包括但不限于 `pixflow-permission/src/main/java/com/pixflow/harness/permission/config/PermissionAutoConfiguration.java`、`pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/config/AuthAutoConfiguration.java`、`pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/config/CommerceAutoConfiguration.java`、`pixflow-conversation/src/main/java/com/pixflow/module/conversation/config/ConversationAutoConfiguration.java`、`pixflow-module-dag/src/main/java/com/pixflow/module/dag/config/DagAutoConfiguration.java`、`pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileAutoConfiguration.java`、`pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/config/ImagegenAutoConfiguration.java`、`pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/config/RubricsAutoConfiguration.java`、`pixflow-module-task/src/main/java/com/pixflow/module/task/config/TaskAutoConfiguration.java`、`pixflow-state/src/main/java/com/pixflow/harness/state/config/StateAutoConfiguration.java`。

第三步是删除所有 `@Qualifier("xxxClock") Clock` 参数，并把参数名改成普通的 `clock` 或保留局部语义名但不再依赖 Bean 名称。比如 `CommerceAutoConfiguration#commerceRowValidator(@Qualifier("commerceClock") Clock commerceClock)` 改为 `commerceRowValidator(Clock clock)`，调用 `new RowValidator(clock)`；`RubricsAutoConfiguration` 中所有 `@Qualifier("rubricsClock") Clock clock` 改为裸 `Clock clock`；`AuthAutoConfiguration` 中 `@Qualifier("authClock") Clock authClock` 改为裸 `Clock clock`。如果某个方法只有参数名叫 `taskClock` 但没有 qualifier，也应改成 `clock`，减少旧概念残留。

第四步是修正 app 层配置。当前 `pixflow-app/src/main/java/com/pixflow/app/task/TaskQueryConfiguration.java` 和 `pixflow-app/src/main/java/com/pixflow/app/download/CustomDownloadConfiguration.java` 使用参数名 `taskClock` 接收 `Clock`。这些参数应改为裸 `Clock clock`，并把传递给 `DownloadService`、`TaskQueryServiceImpl`、`CustomDownloadService` 的变量同步改名。app 层不声明自己的时间源，只消费 common 提供的唯一平台时间源。

第五步是清理测试中的旧假设。纯单元测试可以继续直接使用 `Clock.fixed(...)` 构造服务，不需要改成 Spring Bean。Spring 上下文测试中如果曾断言 `commerceClock`、`taskClock` 这类 Bean 存在，应删除这些断言并改为断言只有一个 `Clock` Bean。例如 `pixflow-module-commerce/src/test/java/com/pixflow/module/commerce/config/CommerceMapperScanBoundaryTest.java` 当前有 `commerceClockIsCreatedByNameWhenOtherClockBeansExist` 这类测试，它验证的是旧设计，必须删除或改写为“不会创建模块私有 clock，且在引入 common 时间自动配置时只有一个 Clock”。如果测试需要固定时间，使用 `ApplicationContextRunner.withBean(Clock.class, () -> Clock.fixed(...))` 覆盖默认 Bean。

第六步是新增守护测试。推荐在 `pixflow-common/src/test/java/com/pixflow/common/time/TimeAutoConfigurationTest.java` 中用 `ApplicationContextRunner` 验证默认创建单个 `Clock`，以及用户自定义 `Clock` 时不再创建默认 `pixflowClock`。再在 `pixflow-app/src/test/java/com/pixflow/app/ClockBeanBoundaryTest.java` 或现有 app 扫描边界测试旁新增一个静态扫描测试，读取主源码文本并断言不存在 `@Qualifier(".*Clock")`、不存在 `@ConditionalOnMissingBean(name = ".*Clock")`、不存在 `public Clock .*Clock()` 这三类模式，唯一允许例外是 `TimeAutoConfiguration#pixflowClock()`。这个测试是防复发的关键。

第七步是运行验证。先跑 `pixflow-common` 测试证明平台时间源自动配置成立，再跑受影响模块测试，最后跑 app 测试和 install。若本机外部 Redis、MySQL、MinIO、RocketMQ 未启动，dev profile 启动可以失败在明确外部依赖连接错误上；不接受再失败在 `Clock` 候选 Bean 歧义上。

## Concrete Steps

从仓库根目录 `D:\study\PixFlow` 开始。先确认工作区状态，避免覆盖无关改动：

    git status --short

当前工作区已有大量未提交改动，执行者只能修改本计划涉及的 clock 自动配置、相关测试和必要文档，不能还原无关文件。

建立 clock 现状清单：

    rg -n "Clock|@Qualifier\\(\".*Clock\"\\)|@ConditionalOnMissingBean\\(name = \".*Clock\"\\)|public Clock .*Clock\\(" pixflow-*\\src\\main\\java

预期会看到多个模块私有 `Clock` Bean 和 qualifier。实施完成后再次运行该命令，主代码中应只剩业务类构造函数或自动配置方法的裸 `Clock` 参数，以及 `TimeAutoConfiguration#pixflowClock()`。

修改 `pixflow-common/pom.xml`，在主依赖中增加：

    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure</artifactId>
    </dependency>

新增 `pixflow-common/src/main/java/com/pixflow/common/time/TimeAutoConfiguration.java`：

    package com.pixflow.common.time;

    import java.time.Clock;
    import org.springframework.boot.autoconfigure.AutoConfiguration;
    import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
    import org.springframework.context.annotation.Bean;

    @AutoConfiguration
    public class TimeAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(Clock.class)
        public Clock pixflowClock() {
            return Clock.systemUTC();
        }
    }

新增 `pixflow-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，内容为：

    com.pixflow.common.time.TimeAutoConfiguration

删除模块私有时间源。逐个打开以下文件，删除 `xxxClock()` Bean 方法，并把所有 `@Qualifier("xxxClock") Clock xxxClock` 改成裸 `Clock clock`：

    pixflow-permission/src/main/java/com/pixflow/harness/permission/config/PermissionAutoConfiguration.java
    pixflow-infra-auth/src/main/java/com/pixflow/infra/auth/config/AuthAutoConfiguration.java
    pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/config/CommerceAutoConfiguration.java
    pixflow-conversation/src/main/java/com/pixflow/module/conversation/config/ConversationAutoConfiguration.java
    pixflow-module-dag/src/main/java/com/pixflow/module/dag/config/DagAutoConfiguration.java
    pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileAutoConfiguration.java
    pixflow-module-imagegen/src/main/java/com/pixflow/module/imagegen/config/ImagegenAutoConfiguration.java
    pixflow-module-rubrics/src/main/java/com/pixflow/module/rubrics/config/RubricsAutoConfiguration.java
    pixflow-module-task/src/main/java/com/pixflow/module/task/config/TaskAutoConfiguration.java
    pixflow-state/src/main/java/com/pixflow/harness/state/config/StateAutoConfiguration.java

同步 app 层配置：

    pixflow-app/src/main/java/com/pixflow/app/task/TaskQueryConfiguration.java
    pixflow-app/src/main/java/com/pixflow/app/download/CustomDownloadConfiguration.java

把 `Clock taskClock` 改为 `Clock clock`，并把方法体中传递的变量同步改名。

清理测试旧假设。重点检查：

    pixflow-module-commerce/src/test/java/com/pixflow/module/commerce/config/CommerceMapperScanBoundaryTest.java
    pixflow-module-file/src/test/java/com/pixflow/module/file/config/FileAutoConfigurationTest.java
    pixflow-app/src/test/java/com/pixflow/app/PixFlowApplicationScanBoundaryTest.java

如果测试显式断言 `commerceClock` 或其他模块私有 Bean 存在，应改成断言唯一 `Clock` 或删除该测试。需要 Spring 上下文提供时间源的 `ApplicationContextRunner` 测试，应加入 `AutoConfigurations.of(TimeAutoConfiguration.class, 被测AutoConfiguration.class)`，或显式 `.withBean(Clock.class, () -> Clock.fixed(...))`。

新增 common 自动配置测试：

    pixflow-common/src/test/java/com/pixflow/common/time/TimeAutoConfigurationTest.java

测试至少覆盖两个场景。第一，只有 `TimeAutoConfiguration` 时，context 有单个 `Clock`，Bean 名为 `pixflowClock`。第二，用户提供自定义 `Clock` 时，context 仍只有单个 `Clock`，且不会创建 `pixflowClock`。

新增防复发测试。推荐路径：

    pixflow-app/src/test/java/com/pixflow/app/ClockBeanBoundaryTest.java

该测试可以用 JDK `Files.walk(Path.of("..").toAbsolutePath().normalize())` 或项目根路径解析所有 `src/main/java` 文件，跳过 `pixflow-common/src/main/java/com/pixflow/common/time/TimeAutoConfiguration.java`，断言主代码不包含以下模式：

    @Qualifier("...Clock")
    @ConditionalOnMissingBean(name = "...Clock")
    public Clock ...Clock(

测试失败信息要指出具体文件路径和匹配行，便于后续贡献者直接修正。

运行验证：

    mvn -pl pixflow-common test
    mvn -pl pixflow-permission,pixflow-infra-auth,pixflow-state,pixflow-module-commerce,pixflow-module-dag,pixflow-module-file,pixflow-module-imagegen,pixflow-module-rubrics,pixflow-module-task -am test
    mvn -pl pixflow-app test
    mvn -pl pixflow-app -am -DskipTests install

如果需要做启动验证，先确保外部依赖已启动，或接受启动推进到真实外部依赖连接阶段：

    mvn -pl pixflow-app spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--debug"

启动日志不应再出现：

    No qualifying bean of type 'java.time.Clock' available: expected single matching bean but found ...

最后把执行结果记录回本计划的 `Progress`、`Surprises & Discoveries`、`Outcomes & Retrospective` 与 `Revision Notes`。

## Validation and Acceptance

第一，主代码中只有一个 `Clock` Bean 声明。运行：

    rg -n "public Clock .*Clock\\(" pixflow-*\\src\\main\\java

预期只命中：

    pixflow-common/src/main/java/com/pixflow/common/time/TimeAutoConfiguration.java

如果命中其他自动配置类，说明模块私有时间源没有删干净。

第二，主代码中没有 clock qualifier。运行：

    rg -n "@Qualifier\\(\".*Clock\"\\)" pixflow-*\\src\\main\\java

预期无输出。任何命中都说明旧的多时间源模型仍残留。

第三，主代码中没有按名称判断 clock 的条件装配。运行：

    rg -n "@ConditionalOnMissingBean\\(name = \".*Clock\"\\)" pixflow-*\\src\\main\\java

预期无输出。

第四，`pixflow-common` 的自动配置测试通过，证明默认时间源和测试覆盖机制都可用。运行：

    mvn -pl pixflow-common test

预期 Maven build success。

第五，受影响模块测试通过。运行：

    mvn -pl pixflow-permission,pixflow-infra-auth,pixflow-state,pixflow-module-commerce,pixflow-module-dag,pixflow-module-file,pixflow-module-imagegen,pixflow-module-rubrics,pixflow-module-task -am test

预期 Maven build success。若某个模块测试因为上下文缺少 `Clock` 失败，应在该测试的 `ApplicationContextRunner` 中导入 `TimeAutoConfiguration` 或提供唯一 `Clock` Bean，而不是恢复模块私有 clock。

第六，app 测试与构建通过。运行：

    mvn -pl pixflow-app test
    mvn -pl pixflow-app -am -DskipTests install

预期 Maven build success。app 上下文或扫描边界测试应证明应用装配中只有一个 `Clock`。

第七，启动验证不再因为 clock 歧义失败。若外部服务未启动，允许失败在 Redis/MySQL/MinIO/RocketMQ 的明确连接错误；不允许出现 `Clock` 候选 Bean 歧义。

## Idempotence and Recovery

本计划可重复执行。重复运行 grep 检查和测试不会修改外部状态。新增的 `TimeAutoConfiguration` 是幂等的：如果测试或 app 显式提供了一个 `Clock`，默认 `pixflowClock` 不会创建；如果没有提供，则创建一个 UTC 系统时间源。

如果删除模块私有 `Clock` 后某个自动配置测试失败，正确恢复方式是让测试导入 `TimeAutoConfiguration` 或提供唯一 `Clock` Bean。不要把 `xxxClock()` Bean 方法加回去，也不要新增 `@Primary` 来掩盖多 Bean 并存。

如果某个业务确实需要不同的时间语义，例如模拟第三方平台时间或处理用户时区，不应新增第二个 `Clock` Bean。应在业务配置中保存 `ZoneId`、`Duration clockSkew` 或业务策略对象，再由服务使用同一个 `Clock` 计算。这保证运行时“当前时间来源”仍唯一。

如果 app 层需要在特定 profile 固定时间用于演示，应通过 profile 专属配置提供唯一 `Clock` Bean 覆盖 `pixflowClock`，而不是恢复模块私有 Bean。

## Artifacts and Notes

最近一次错误日志的关键片段：

    No qualifying bean of type 'java.time.Clock' available:
    expected single matching bean but found 8:
    permissionClock,authClock,commerceClock,conversationClock,dagClock,imagegenClock,rubricsClock,taskClock

本计划开始前的典型旧模式：

    @Bean
    @ConditionalOnMissingBean(name = "taskClock")
    public Clock taskClock() {
        return Clock.systemUTC();
    }

    public DownloadService downloadService(..., Clock taskClock) {
        return new DownloadService(..., taskClock);
    }

或：

    public FreshnessPolicy freshnessPolicy(
            CommerceProperties properties,
            @Qualifier("commerceClock") Clock commerceClock) {
        return new FreshnessPolicy(properties, commerceClock);
    }

本计划完成后的目标模式：

    @Bean
    @ConditionalOnMissingBean(Clock.class)
    public Clock pixflowClock() {
        return Clock.systemUTC();
    }

    public DownloadService downloadService(..., Clock clock) {
        return new DownloadService(..., clock);
    }

    public FreshnessPolicy freshnessPolicy(CommerceProperties properties, Clock clock) {
        return new FreshnessPolicy(properties, clock);
    }

测试覆盖唯一时间源的目标模式：

    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(TimeAutoConfiguration.class, CommerceAutoConfiguration.class))
        .withBean(Clock.class, () -> Clock.fixed(Instant.parse("2026-07-05T00:00:00Z"), ZoneOffset.UTC))
        .run(context -> assertThat(context).hasSingleBean(Clock.class));

## Interfaces and Dependencies

新增自动配置类：

    pixflow-common/src/main/java/com/pixflow/common/time/TimeAutoConfiguration.java

接口形态：

    @AutoConfiguration
    public class TimeAutoConfiguration {
        @Bean
        @ConditionalOnMissingBean(Clock.class)
        public Clock pixflowClock() {
            return Clock.systemUTC();
        }
    }

自动配置注册文件：

    pixflow-common/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

内容：

    com.pixflow.common.time.TimeAutoConfiguration

Maven 依赖：

    pixflow-common/pom.xml
      -> org.springframework.boot:spring-boot-autoconfigure

运行时契约：

    Spring 容器中最多只能有一个 java.time.Clock Bean。
    默认 Bean 名称是 pixflowClock。
    默认实现是 Clock.systemUTC()。
    模块服务与自动配置只注入 Clock 类型，不注入命名 Clock。
    测试可以提供唯一 Clock Bean 覆盖默认实现。

禁止的主代码模式：

    @Qualifier("xxxClock") Clock
    @ConditionalOnMissingBean(name = "xxxClock")
    public Clock xxxClock()
    多个 Clock Bean 并存后通过 @Primary 兜底

允许的主代码模式：

    private final Clock clock;
    public SomeService(..., Clock clock) { ... }
    Instant.now(clock)

允许的测试代码模式：

    Clock.fixed(...)
    Clock.systemUTC()
    ApplicationContextRunner.withBean(Clock.class, ...)

## Revision Notes

2026-07-05 / Codex: 新建本 ExecPlan。计划针对多模块私有 `Clock` Bean 导致的 Spring 注入歧义，提出完整重构方案：在 `pixflow-common` 提供唯一平台级 `TimeAutoConfiguration`，删除所有模块私有 `*Clock` Bean 和 `@Qualifier("xxxClock")`，让所有模块统一注入裸 `Clock`，并通过守护测试防止旧模式复发。该方案不保留迁移式兼容旧代码，目标是一次性消除同类型时间源 Bean 的结构性风险。

2026-07-05 / Codex: 完成本 ExecPlan 实施并更新记录。新增 common 平台时间源自动配置与测试，删除所有已发现模块私有 Clock Bean 和 Clock qualifier，补 app 源码扫描守护测试，更新 commerce/file/state/imagegen 等上下文测试导入唯一平台 Clock。实施中发现 `pixflow-module-memory` 也有 `memoryClock()`，为满足“运行时唯一 Clock”契约一并删除。记录验证结果以及 `pixflow-infra-image` 既有测试编译问题对部分 `-am test` 的阻断。

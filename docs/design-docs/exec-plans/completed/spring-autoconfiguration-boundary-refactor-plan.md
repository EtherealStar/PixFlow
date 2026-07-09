# 重构 Spring Boot 自动配置边界，彻底修复模块 Bean 装配顺序问题

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`、`AGENTS.md`、`docs/design-docs/index.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`、`docs/design-docs/exec-plans/rabbitmq-to-rocketmq-refactor-plan.md`、`docs/design-docs/module/file.md`、`docs/design-docs/infra/storage.md` 与 `docs/design-docs/infra/mq.md`。后续执行者修改本计划时，必须保持它自包含、可验证、可恢复，并在文末记录修改原因。

## Purpose / Big Picture

当前启动失败表面上是 `FileController` 找不到 `FileService`，但真正的问题是 PixFlow 的 Spring 装配体系混用了两套机制：一部分模块通过 Spring Boot `AutoConfiguration.imports` 进入容器，另一部分基础设施模块靠 `@SpringBootApplication` 从 `com.pixflow` 根包做普通组件扫描碰巧进入容器。结果是业务模块的条件式 Bean 在判断时可能看不到上游基础设施 Bean，controller 却已经被普通扫描注册，最终出现“controller 存在、service 不存在”的启动错误。

完成本重构后，用户可以用同一套启动方式稳定启动 `pixflow-app`，模块能力由明确的 Spring Boot 自动配置导入、排序和条件控制，不再依赖全包扫描的偶然顺序。可观察结果是：`FileService` 与 `FileController` 要么一起装配成功，要么在缺少真实基础设施配置时给出直接、准确的缺失依赖错误；不会再出现 `FileController` 抢先注册后找不到 `FileService` 的间接错误。

## Progress

- [x] (2026-07-03 20:12+08:00) 已定位根因：当时 `pixflow-module-file` 有 `AutoConfiguration.imports`，但 `pixflow-infra-storage` 和 `pixflow-infra-mq` 没有对应 imports；`FileController` 通过根包组件扫描无条件注册，而 `FileService` 由 `FileAutoConfiguration` 条件式创建。
- [x] (2026-07-03 20:12+08:00) 已读取 `PLANS.md`、`docs/design-docs/index.md`、`docs/design-docs/module/file.md`、`docs/design-docs/infra/storage.md` 与 `docs/design-docs/infra/mq.md`，确认 file 依赖 storage 与 mq 是设计允许的 Wave 2 对 Wave 1 依赖。
- [x] (2026-07-03 本次记录) 已给当前工作树中可复用模块补齐自动配置 imports。`rg --files | rg "AutoConfiguration\\.imports$"` 当前能看到 infra、harness、module、agent、permission、tools、loop 等模块的 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。
- [x] (2026-07-03 本次记录) 已将主要可复用模块配置类统一为 `@AutoConfiguration`。当前 `rg -n "@AutoConfiguration" -g "*.java"` 覆盖 `pixflow-infra-*`、`pixflow-module-*`、`pixflow-agent`、`pixflow-conversation`、`pixflow-hooks`、`pixflow-state`、`pixflow-session`、`pixflow-tools`、`pixflow-loop`、`pixflow-eval`、`pixflow-permission`。
- [x] (2026-07-03 本次记录) 已收窄 app 扫描边界。启动类从 `pixflow-app/src/main/java/com/pixflow/PixFlowApplication.java` 移到 `pixflow-app/src/main/java/com/pixflow/app/PixFlowApplication.java`；由于启动类包名变为 `com.pixflow.app`，默认组件扫描只覆盖 app 层。该类显式 `@Import(HttpErrorRenderer.class)` 保留 common 错误渲染，并增加 app 局部 `MapperScannerConfigurer`。
- [x] (2026-07-03 本次记录) 已将多个模块 controller 改为由自动配置显式创建 bean。已确认 `FileAutoConfiguration` 创建 `FileController`，`CommerceAutoConfiguration` 创建 `CommerceController`，`ConversationAutoConfiguration` 创建 conversation/history/message/confirmation/cancellation controllers，`RubricsAutoConfiguration` 创建 rubrics admin/report controllers。controller 类上的 `@RestController` 注解当前仍保留，但 app 已不再扫描 `com.pixflow.module.*`，实际入口由自动配置中的 `@Bean` 控制。
- [x] (2026-07-03 本次记录) 已修复多处 MyBatis mapper 扫描边界，改用精确包或 `basePackageClasses`，并增加 `annotationClass = Mapper.class`。已确认 file、commerce、conversation、agent、task、rubrics、dag、session、memory、auth、vision 等模块的主配置采用收窄后的 `@MapperScan`。
- [x] (2026-07-03 本次记录) 已把 file/imagegen 的缺失 SPI 接上。`pixflow-module-file` 依赖 `pixflow-module-imagegen`，新增 `DefaultSourceImageReader` 实现 `SourceImageReader`，并在 `FileAutoConfiguration` 中暴露 `SourceImageReader` bean。
- [x] (2026-07-03 本次记录) 已修复 conversation 中 pending plan 装配被条件静默跳过的问题。`ConversationAutoConfiguration#pendingProposalRepository` 与 `#pendingPlanPort` 已移除对 `PendingPlanMapper` 的 `@ConditionalOnBean`，让 mapper 作为直接必需依赖暴露。
- [x] (2026-07-03 本次记录) 已给 permission 模块增加自动配置入口。新增 `PermissionAutoConfiguration`、`PermissionProperties`、`pixflow-permission/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，并在 `pixflow-permission/pom.xml` 增加 Spring Boot autoconfigure 与 configuration processor 依赖。
- [x] (2026-07-03 本次记录) 已修复 tools 模块的装配缺口。`ToolsAutoConfiguration` 已改为 `@AutoConfiguration`，新增默认 `PlanModeView` bean；`ToolExecutionContext`、`ToolVisibilityContext`、`RegistryToolExecutor`、`ToolsAutoConfiguration` 已使用全限定 permission 类型修正字节码签名。
- [x] (2026-07-03 本次记录) 已完成多轮局部验证：`mvn -pl pixflow-module-file -am test` 通过；`mvn -pl pixflow-module-commerce -am test` 通过；`mvn -pl pixflow-permission -am test` 通过；`mvn -pl pixflow-tools -am -DskipTests compile` 通过；`mvn -pl pixflow-app -am -DskipTests clean package` 曾在较早阶段通过。
- [x] (2026-07-03 本次记录) 已用 `javap` 验证 tools 模块相关 class 的方法签名引用 `com.pixflow.harness.permission.PermissionPolicy` 与 `com.pixflow.harness.permission.PermissionContext`，不再落到默认包签名。
- [x] (2026-07-03 23:20+08:00) 已完成最终验证：`mvn -pl pixflow-app -am -DskipTests install` 通过；`mvn -pl pixflow-app spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--debug"` 已推进到真实外部依赖连接阶段，失败点是 Redis `localhost:6379` connection refused，不再是 `SourceImageReader`、`PendingPlanPort`、`PermissionPolicy`、`PlanModeView`、tools 签名、controller/service 或 mapper 扫描边界问题。
- [x] (2026-07-03 23:18+08:00) 已补强关键守护测试：新增 file 自动配置测试，断言 `FileService`、`FileController`、`SourceImageReader` 在必需端口存在时一起装配；新增 commerce mapper 扫描边界测试，断言 `PlatformApiClient` 不是 MyBatis mapper；新增 app 扫描边界测试，断言启动类位于 `com.pixflow.app` 且未显式扩大 `scanBasePackages`。
- [ ] 待后续补强：仍建议增加更完整的 app smoke 上下文测试，用 fake Redis/MySQL/MinIO/RocketMQ 或专用 test profile 验证全应用上下文可启动；当前 dev profile 验证依赖本机外部服务，未在无外部服务条件下做到 HTTP 200 级 smoke。

## Surprises & Discoveries

- Observation: `FileController` 是 `@RestController`，位于 `com.pixflow.module.file.web`，因此会被 `com.pixflow.PixFlowApplication` 的根包扫描发现；`FileService` 没有 `@Service`，只在 `FileAutoConfiguration#fileService` 中通过 `@Bean` 创建。
  Evidence: `pixflow-module-file/src/main/java/com/pixflow/module/file/web/FileController.java` 构造函数依赖 `FileService`；`pixflow-module-file/src/main/java/com/pixflow/module/file/FileService.java` 是普通类；`pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileAutoConfiguration.java` 暴露 `fileService(...)`。

- Observation: `FileAutoConfiguration#fileService` 受 `@ConditionalOnBean({AssetPackageService.class, AssetPackageMapper.class, AssetIngestErrorMapper.class, ObjectStorage.class, ExtractionPublisher.class})` 控制。只要 `ObjectStorage`、`MessagePublisher` 或 mapper 定义在条件判断时尚未出现，`FileService` 就会被跳过。
  Evidence: `FileAutoConfiguration.java` 中 `fileService(...)` 上的 `@ConditionalOnBean` 包含 `ObjectStorage` 和 `ExtractionPublisher`；`ExtractionPublisher` 又依赖 `MessagePublisher`。

- Observation: 计划开始时，`pixflow-infra-storage` 和 `pixflow-infra-mq` 没有 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，但 file、commerce、memory、task、vision 等模块有。基础设施配置与业务模块配置不在同一套自动配置发现与排序体系里。
  Evidence: `rg --files | rg "AutoConfiguration\\.imports$"` 能看到 `pixflow-module-file` 等模块 imports，但没有 `pixflow-infra-storage` 和 `pixflow-infra-mq`。

- Observation: 计划开始后的 app 启动曾先遇到 `PlatformApiClient` 被 MyBatis mapper scanner 扫描冲突的问题。这说明 mapper 扫描边界也存在同类“扫描范围过宽”的结构性问题，应在同一轮修复，而不是只处理 file。
  Evidence: 启动日志报 `ConflictingBeanDefinitionException: Annotation-specified bean name 'platformApiClient' for bean class [com.pixflow.module.commerce.source.PlatformApiClient] conflicts ...`。

- Observation: 旧的 root package 扫描被移除后，会连续暴露出以前被全包扫描或条件装配掩盖的缺失 bean。当前已依次暴露并修复 `SourceImageReader`、`PendingPlanPort`、`PermissionPolicy`、`PlanModeView` 和 tools permission 字节码签名问题。
  Evidence: 本轮启动推进过程中，先后补充 `DefaultSourceImageReader`、移除 conversation pending plan 的 mapper 条件、增加 `PermissionAutoConfiguration`、增加默认 `PlanModeView`，并用 `javap` 确认 tools class 签名引用 `com.pixflow.harness.permission.*`。

- Observation: `@ConditionalOnBean` 对 MyBatis mapper bean 的判断时机不稳定，尤其不适合用于同一自动配置类中核心 service 或 SPI 的前置条件。
  Evidence: `FileAutoConfiguration#sourceImageReader` 最初依赖 `@ConditionalOnBean(AssetImageMapper.class)` 时，启动仍可能缺少 `SourceImageReader`；移除该条件并把 `AssetImageMapper` 作为直接方法参数后，缺依赖能在真实依赖处暴露。

- Observation: commerce 模块也存在同类核心门面被条件静默跳过的问题；`CommerceController` 会无条件创建，但 `CommerceService` 曾因 `@ConditionalOnBean` 判断 mapper 时机不满足而没有创建。
  Evidence: 新增 `CommerceMapperScanBoundaryTest` 初次运行时，上下文失败于 `commerceController` 缺少 `CommerceService`；移除 `CommerceImportService`、`CommerceApiImportPublisher`、`CommerceImportJobService`、`CommerceQueryService`、`CommerceService` 上隐藏核心门面的 `@ConditionalOnBean` 后，`mvn -pl pixflow-module-commerce -am test` 通过。

- Observation: controller 类当前仍保留 `@RestController`，但由于 app 启动类已经移动到 `com.pixflow.app`，这些模块 controller 不再被根包扫描发现；只有自动配置中的 `@Bean` 会把它们放入容器。
  Evidence: `rg -n "@RestController|@Controller" -g "*.java"` 仍能看到 `pixflow-module-file`、`pixflow-module-commerce`、`pixflow-conversation`、`pixflow-module-rubrics` 的 controller 注解；同时 `PixFlowApplication` 位于 `com.pixflow.app`，模块自动配置中存在对应 controller bean 方法。

- Observation: dev profile 启动已经越过 Spring 自动配置边界问题，当前本机失败在真实 Redis 连接。
  Evidence: `mvn -pl pixflow-app spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--debug"` 日志显示 `@EnableAutoConfiguration` 包为 `com.pixflow.app`，Tomcat 已初始化，最终失败为 `org.redisson.client.RedisConnectionException: Unable to connect to Redis server: localhost/127.0.0.1:6379`。

## Decision Log

- Decision: 本次修复不采用“给 `FileService` 加 `@Service`”作为最终方案。
  Rationale: 这只能让 `FileService` 也被根包扫描捞进容器，绕过一次症状，但继续依赖全包扫描和不可控顺序。它无法解决其他模块未来出现同类 controller/service、mapper、listener 装配问题。
  Date/Author: 2026-07-03 / Codex

- Decision: 统一采用 Spring Boot 自动配置作为可复用模块的唯一装配入口，app 只扫描 `com.pixflow.app` 自己的应用层组件。
  Rationale: PixFlow 是多 Maven 模块架构。可复用模块应通过 `AutoConfiguration.imports` 显式进入容器，这样可以排序、测试、按条件启用；app 层只负责启动类、WebSocket、安全入口、环境配置和少量应用级适配器。
  Date/Author: 2026-07-03 / Codex

- Decision: 模块 controller 必须跟随对应 service 装配，不再作为普通 `@RestController` 被全包扫描无条件注册。
  Rationale: controller 是 HTTP 入口，它没有独立存在价值。如果核心 service 因缺配置或缺依赖无法创建，controller 也应不创建，或直接让缺失依赖在 service 创建处暴露，而不是报间接的构造函数注入错误。
  Date/Author: 2026-07-03 / Codex

- Decision: `@ConditionalOnBean` 只用于可选能力，不用于隐藏核心业务门面。
  Rationale: `FileService`、`CommerceService` 这类核心门面如果缺上游依赖，应让 Spring 给出明确缺失依赖，而不是静默跳过。静默跳过会把错误推迟到 controller 或其他消费者处，降低可诊断性。
  Date/Author: 2026-07-03 / Codex

- Decision: commerce 核心服务链移除 mapper/MQ 前置 `@ConditionalOnBean`，保留 listener container 等可选运行能力的条件装配。
  Rationale: `CommerceService` 与 `CommerceController` 是同一个 REST 能力边界，应同生共死。mapper 或 `MessagePublisher` 缺失时应直接暴露真实缺依赖，而不是跳过 service 后让 controller 报间接错误。消费者容器依赖 broker/factory，属于可选运行能力，继续用条件装配。
  Date/Author: 2026-07-03 / Codex

- Decision: 当前实现优先通过“移动 app 启动类包名 + 自动配置显式 controller bean”切断根包扫描，而不是在同一轮强制移除所有 controller 类上的 `@RestController` 注解。
  Rationale: 移动启动类到 `com.pixflow.app` 已经使 `com.pixflow.module.*` controller 不再被普通扫描注册；自动配置 bean 方法控制 controller 生命周期，满足本计划的装配边界目标。保留注解可降低一次性改动范围，后续可以作为清理项在测试补齐后再移除。
  Date/Author: 2026-07-03 / Codex

- Decision: 对 file/imagegen 的 `SourceImageReader` 采用由 file 模块实现 imagegen SPI 的方向。
  Rationale: imagegen 需要按 package/image id 读取源图信息，而这些数据属于 file 模块的资产表与 mapper。让 file 依赖 imagegen 的小型 SPI 并提供实现，避免 imagegen 反向依赖 file 的存储细节。
  Date/Author: 2026-07-03 / Codex

- Decision: permission 模块必须提供自己的自动配置，而不是继续依靠其他模块或根包扫描间接获得 `PermissionPolicy`。
  Rationale: tools、loop 等 harness 模块需要稳定注入 `PermissionPolicy`。把 `DefaultPermissionPolicy`、`ConfirmationTokenService` 与相关 properties 放进 `PermissionAutoConfiguration`，才能在 app 收窄扫描后保持可复用模块自装配。
  Date/Author: 2026-07-03 / Codex

- Decision: tools 模块的 permission 类型引用使用全限定名，先修正当前工作树中的字节码签名问题。
  Rationale: 编译链路中曾出现 tools class 对默认包 `PermissionPolicy` / `PermissionContext` 的错误签名，导致下游 `pixflow-loop` 编译失败。使用全限定类型可以消除 import 或同名解析导致的歧义，并已通过 `javap` 验证。
  Date/Author: 2026-07-03 / Codex

## Outcomes & Retrospective

截至 2026-07-03 本次记录，本计划已经完成主要结构性重构：可复用模块开始通过 Spring Boot 自动配置 imports 进入容器，app 启动类移动到 `com.pixflow.app` 后不再扫描整个 `com.pixflow` 根包，多个模块 controller 已由各自自动配置显式创建，mapper scan 也已从模块根包收窄到 mapper 所在包或 mapper marker 类型。

已验证的结果包括：`pixflow-module-file` 测试通过，`pixflow-module-commerce` 测试通过，`pixflow-permission` 测试通过，`pixflow-tools` 编译通过；较早阶段 `pixflow-app` 的 `clean package` 也曾通过。逐步启动暴露出的缺口已经修复到 tools 字节码签名层面，包括 file/imagegen 的 `SourceImageReader`、conversation 的 `PendingPlanPort`、permission 的 `PermissionPolicy`、tools 的默认 `PlanModeView`。

最终闭环验证已完成到当前环境允许的边界。`mvn -pl pixflow-app -am -DskipTests install` 通过；dev profile 启动不再失败于 Spring 装配边界问题，而是失败在真实 Redis 连接拒绝，这符合本计划对外部服务未启动时的验收口径。新增的守护测试覆盖了 file 的 service/controller/source-image SPI 同生共死、commerce 的 mapper 扫描边界，以及 app 启动类不再扩大到 `com.pixflow` 根包扫描。

本轮的主要经验是：收窄 app 扫描后会暴露一串以前被根包扫描掩盖的问题，这正是本计划要消除的结构性风险。后续继续推进时，应优先补最终 smoke 测试和自动配置测试，再考虑移除 controller 类上残留的 `@RestController` 注解这类清理项。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。项目是 Spring Boot 3.5 多模块 Maven 工程。主应用模块是 `pixflow-app`。本计划开始时启动类位于 `pixflow-app/src/main/java/com/pixflow/PixFlowApplication.java`，因此默认 `@SpringBootApplication` 会扫描所有 `com.pixflow.*` 包下的 `@Component`、`@Controller`、`@Service`、`@Configuration` 等注解类。当前工作树中启动类已经移动到 `pixflow-app/src/main/java/com/pixflow/app/PixFlowApplication.java`，包名是 `com.pixflow.app`，默认扫描范围因此收窄为 app 层。

Spring Boot 自动配置是另一套机制。一个可复用 jar 可以在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中登记自己的配置类，Spring Boot 启动时会读取这些文件并导入配置类。自动配置类通常使用 `@AutoConfiguration`，可以通过 `@AutoConfiguration(after = SomeAutoConfiguration.class)` 明确顺序，也可以通过 `@ConditionalOnBean`、`@ConditionalOnProperty` 等条件控制是否创建某个 bean。

原始出错链路如下。`pixflow-module-file/src/main/java/com/pixflow/module/file/web/FileController.java` 是 `@RestController`，会被旧的根包扫描立即注册。它构造函数需要 `FileService`。`FileService` 是普通类，没有 `@Service`，只由 `pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileAutoConfiguration.java` 的 `fileService(...)` 方法创建。这个方法曾要求 `ObjectStorage`、`ExtractionPublisher`、mapper 等 bean 已经存在。`ObjectStorage` 由 `pixflow-infra-storage` 创建，`ExtractionPublisher` 依赖 `pixflow-infra-mq` 创建的 `MessagePublisher`。计划开始时 `pixflow-infra-storage` 与 `pixflow-infra-mq` 没有自己的 `AutoConfiguration.imports`，它们不在同一套可排序的自动配置体系里。当前工作树已经给 storage、mq 和其他可复用模块补齐 imports，并在 `FileAutoConfiguration` 中让 `FileService` 与 `FileController` 通过显式 bean 方法创建。

需要理解几个术语。Bean 是 Spring 容器管理的对象，例如 `FileService`。Controller 是接收 HTTP 请求的 bean，例如 `FileController`。Mapper 是 MyBatis 创建的数据库访问接口，例如 `AssetPackageMapper`。自动配置 imports 是 Spring Boot 发现模块配置类的清单文件。根包扫描是 `@SpringBootApplication` 默认从启动类所在包递归扫描注解类的行为。

## Plan of Work

第一阶段建立自动配置清单。检查所有 Maven 模块中是否存在名字类似 `*AutoConfiguration` 的配置类。对可复用模块补齐 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`。本阶段必须至少补齐 `pixflow-infra-storage` 和 `pixflow-infra-mq`，因为 file 模块直接依赖它们。若 `pixflow-infra-cache`、`pixflow-context`、`pixflow-hooks`、`pixflow-loop` 等模块存在自动配置类但没有 imports，也应纳入同一轮梳理。完成后，所有可复用模块不再依赖 app 根包扫描发现配置类。

第二阶段统一配置类注解和排序。把可复用模块配置类统一改为 `@AutoConfiguration`。对 file 模块，将 `FileAutoConfiguration` 声明为在 `StorageAutoConfiguration`、`MqAutoConfiguration` 和 MyBatis 自动配置之后运行。对依赖 cache、ai、vector、storage、mq 的模块，也按真实依赖写清楚 `after`。排序只表达模块装配依赖，不改变业务依赖 DAG。比如 file 依赖 storage 与 mq，是 Wave 2 依赖 Wave 1，合法；storage 和 mq 不能反向依赖 file。

第三阶段收窄 app 组件扫描。修改 `PixFlowApplication`，让它位于并只扫描 `com.pixflow.app`。这样 app 层的 `AuthController`、`ProgressMessagingConfig`、`StompProgressNotifier` 等仍然通过普通扫描进入容器；所有 `com.pixflow.module.*`、`com.pixflow.infra.*`、`com.pixflow.harness.*` 的 bean 必须通过自动配置进入。当前工作树已经通过移动启动类包名完成这一步，而不是通过显式 `scanBasePackages` 参数完成。这个改变会暴露所有遗漏 imports，是本重构的关键验收点。

第四阶段迁移模块 controller。以 file 模块为首个修复对象，将 `FileController` 从普通扫描模型迁移到自动配置模型。当前工作树采用的做法是在 `FileAutoConfiguration` 中新增 `@Bean` 方法创建 `FileController`，方法参数为 `FileService`；app 不再扫描模块包，因此 controller 不会靠普通扫描进入容器。这个 `@Bean` 可以使用 `@ConditionalOnMissingBean`，但不应在缺少 `FileService` 时无条件注册。commerce、conversation、rubrics 等模块已按相同模式显式创建 controller bean。controller 类上的 `@RestController` 注解当前仍保留，它不再导致根包扫描泄漏；后续若补齐 smoke 测试，可以把移除这些残留注解作为清理项执行。

第五阶段调整核心门面的条件。对 `FileAutoConfiguration#fileService`，去掉隐藏核心门面的 `@ConditionalOnBean`。保留构造参数依赖，让 Spring 在缺少 `ObjectStorage`、`MessagePublisher` 或 mapper 时直接报缺失依赖。对 `AssetPackageService` 这类核心业务 service 也采用同样策略。可选 worker、listener、destination registration 等可以继续使用 `@ConditionalOnBean`，因为它们确实是可选运行能力或依赖外部 broker 的能力。

第六阶段修复 mapper 扫描边界。每个模块的 `@MapperScan` 必须只扫描 mapper 接口所在包，并配合 `annotationClass = Mapper.class` 或 marker interface，避免把普通业务接口扫描为 mapper。commerce 当前应重点检查 `CommerceAutoConfiguration` 的 `@MapperScan`，确保只扫 `com.pixflow.module.commerce.store` 或更精确的 mapper 包，不扫 `com.pixflow.module.commerce.source`，否则 `PlatformApiClient` 会被误判为 mapper。

第七阶段补测试。每个被改动的模块至少有一个自动配置测试。file 模块用 `ApplicationContextRunner` 提供 fake `ObjectStorage`、fake `MessagePublisher`、必要 mapper 或 MyBatis 测试上下文，断言 `FileService` 和 `FileController` 同时存在。app 模块增加 smoke 测试或启动验证，证明收窄扫描后自动配置仍能导入模块。mapper 边界测试要证明 `PlatformApiClient` 不是 mapper bean。

第八阶段运行验证并记录结果。先跑局部模块测试，再跑 app package，最后用 dev profile 启动应用。如果外部 MySQL、Redis、MinIO、RocketMQ 未启动，则允许启动失败在明确的外部连接错误上；不接受失败在 `FileController` 缺 `FileService`、`PlatformApiClient` mapper 冲突、或某个模块核心 bean 被条件静默跳过。

## Concrete Steps

从仓库根目录 `D:\study\PixFlow` 执行。

先建立现状清单：

    rg -n "@AutoConfiguration|class .*AutoConfiguration|@Configuration" pixflow-*\\src\\main\\java
    rg --files | rg "AutoConfiguration\\.imports$"
    rg -n "@RestController|@Controller|@MapperScan" pixflow-*\\src\\main\\java

计划开始时预期能看到 `pixflow-module-file` 有 imports，而 `pixflow-infra-storage` 和 `pixflow-infra-mq` 缺 imports。当前工作树中 storage、mq 和其他可复用模块已经补齐 imports。`FileController` 当前仍是 `@RestController`，但不会再被 app 根包扫描注册。

补齐基础设施自动配置 imports。新增：

    pixflow-infra-storage/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

内容为：

    com.pixflow.infra.storage.config.StorageAutoConfiguration

新增：

    pixflow-infra-mq/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

内容为：

    com.pixflow.infra.mq.config.MqAutoConfiguration

如果 `pixflow-infra-cache` 等其他基础设施模块也缺 imports，按同样规则补齐。

修改配置类注解。把 `StorageAutoConfiguration`、`MqAutoConfiguration` 从普通 `@Configuration` 改为 `@AutoConfiguration`。保留原有 `@EnableConfigurationProperties` 和条件。对 `FileAutoConfiguration` 添加类似下面的顺序声明：

    @AutoConfiguration(after = {
        StorageAutoConfiguration.class,
        MqAutoConfiguration.class,
        MybatisAutoConfiguration.class
    })

实际代码需要 import `StorageAutoConfiguration`、`MqAutoConfiguration` 与 Spring Boot 的 MyBatis 自动配置类。如果项目使用的是 MyBatis-Plus starter，选择当前依赖中真实存在的 MyBatis auto-configuration 类型；无法直接引用时，用 `@AutoConfigureAfter(name = "...")` 形式按类名声明。

收窄 app 扫描。计划开始时文件是：

    pixflow-app/src/main/java/com/pixflow/PixFlowApplication.java

当前工作树已经删除旧文件，并新增：

    pixflow-app/src/main/java/com/pixflow/app/PixFlowApplication.java

该类位于 `com.pixflow.app` 包，使用默认 `@SpringBootApplication` 即只扫描 app 包。它还显式 `@Import(HttpErrorRenderer.class)`，并通过 `MapperScannerConfigurer` 将 app 自身 mapper 扫描限制在 `com.pixflow.app` 且要求 `@Mapper`。

这一步之后，模块 controller 不会再被普通扫描自动发现，必须由模块自动配置显式暴露。

迁移 file controller。修改：

    pixflow-module-file/src/main/java/com/pixflow/module/file/web/FileController.java

原计划建议移除 `@RestController`，保留 `@Validated` 和 mapping 注解。当前工作树尚未移除该注解；这是允许的中间状态，因为 app 已不再扫描模块包。已经在：

    pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileAutoConfiguration.java

新增：

    @Bean
    @ConditionalOnMissingBean
    public FileController fileController(FileService fileService) {
        return new FileController(fileService);
    }

同一文件中，已经删除 `fileService(...)` 上隐藏核心门面的 `@ConditionalOnBean`。如果删除后缺少依赖导致测试失败，应补齐自动配置顺序或 fake bean，而不是把条件加回去。后续清理可以在自动配置 smoke 测试补齐后再移除 controller 类上的残留 `@RestController`。

修复 mapper 扫描。检查：

    pixflow-module-commerce/src/main/java/com/pixflow/module/commerce/config/CommerceAutoConfiguration.java

确保 `@MapperScan` 只覆盖 `CommerceDataMapper`、`CommerceImportJobMapper` 所在包，不能覆盖 `source` 包。file、memory、task、rubrics、vision 也按同样原则检查。

新增测试。建议文件：

    pixflow-module-file/src/test/java/com/pixflow/module/file/config/FileAutoConfigurationTest.java
    pixflow-module-commerce/src/test/java/com/pixflow/module/commerce/config/CommerceMapperScanBoundaryTest.java
    pixflow-app/src/test/java/com/pixflow/app/AppAutoConfigurationSmokeTest.java

当前已新增：

    pixflow-module-file/src/test/java/com/pixflow/module/file/config/FileAutoConfigurationTest.java
    pixflow-module-commerce/src/test/java/com/pixflow/module/commerce/config/CommerceMapperScanBoundaryTest.java
    pixflow-app/src/test/java/com/pixflow/app/PixFlowApplicationScanBoundaryTest.java

`FileAutoConfigurationTest` 证明在满足 storage、mq、mapper 依赖时，`FileService`、`FileController` 与 `SourceImageReader` 同时存在。`CommerceMapperScanBoundaryTest` 证明 `PlatformApiClient` 不是 MyBatis mapper bean，并守护 mapper scanner 只注册 store 包中的两个 mapper。`PixFlowApplicationScanBoundaryTest` 证明启动类位于 `com.pixflow.app` 且没有通过 `scanBasePackages` 扩大回根包。后续仍可增加一个使用 fake 外部基础设施的 app smoke 上下文测试。

运行验证：

    mvn -pl pixflow-infra-storage,pixflow-infra-mq,pixflow-module-file -am test
    mvn -pl pixflow-module-commerce -am test
    mvn -pl pixflow-app -am -DskipTests package
    mvn -pl pixflow-app spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--debug"

本轮新增验证已运行并通过：

    mvn -pl pixflow-module-file -am test
    mvn -pl pixflow-module-commerce -am test
    mvn -pl pixflow-app test
    mvn -pl pixflow-tools -am -DskipTests compile
    mvn -pl pixflow-app -am -DskipTests install

其中 `mvn -pl pixflow-app spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--debug"` 在本机未启动 Redis 的情况下失败于：

    org.redisson.client.RedisConnectionException: Unable to connect to Redis server: localhost/127.0.0.1:6379

这属于明确外部依赖连接错误，不属于本计划要修复的 Spring 装配边界问题。

最后检查不再有模块 controller 被根包扫描依赖：

    rg -n "@RestController|@Controller" pixflow-module-*\\src\\main\\java pixflow-conversation\\src\\main\\java

允许保留的情况必须有明确理由，例如该模块仍处于待迁移阶段，并在本计划的 `Progress` 中记录。

## Validation and Acceptance

验收标准是启动装配行为稳定，而不只是编译通过。

第一，`pixflow-module-file` 的自动配置测试通过。测试必须断言 `FileService` 和 `FileController` 同时存在，并且 `FileController` 不再依赖根包扫描注册。

第二，`pixflow-app` 在收窄扫描后仍能 package 成功。运行：

    mvn -pl pixflow-app -am -DskipTests package

预期结果是 Maven build success。

第三，启动 dev profile 时不再出现以下错误：

    Parameter 0 of constructor in com.pixflow.module.file.web.FileController required a bean of type 'com.pixflow.module.file.FileService' that could not be found

也不再出现：

    ConflictingBeanDefinitionException ... PlatformApiClient

如果外部 RocketMQ、MySQL、Redis、MinIO 未启动，应用可以失败在明确的连接错误上，例如 broker 连接失败或 datasource 连接失败。这类错误说明装配已经走到真实基础设施连接阶段，不属于本计划要修复的 Spring 装配边界问题。

第四，条件报告中 `FileAutoConfiguration`、`StorageAutoConfiguration`、`MqAutoConfiguration` 都应作为自动配置类出现。`FileAutoConfiguration#fileService` 不应因为 `@ConditionalOnBean` 静默不匹配而消失。

第五，mapper 扫描边界测试通过，普通业务接口不会被 MyBatis mapper scanner 注册为 mapper。

## Idempotence and Recovery

本计划的步骤可重复执行。重复新增 `AutoConfiguration.imports` 时应保持文件内容只有目标配置类的全限定名，每行一个。重复运行测试不应修改数据库或对象存储。迁移 controller 时，如果某个模块临时无法迁移，应保留原注解并在本计划 `Progress` 记录原因，不要半迁移导致接口消失。

如果收窄 app 扫描后出现大量缺 bean，不要把 `scanBasePackages` 改回 `com.pixflow`。正确恢复方式是逐个补齐缺失模块的 `AutoConfiguration.imports` 或自动配置 bean。根包扫描是这类问题的来源，不能作为长期兜底。

如果某个核心 service 删除 `@ConditionalOnBean` 后暴露缺依赖错误，应优先判断这个依赖是设计必需还是可选。如果是必需依赖，补齐上游自动配置和顺序；如果是可选能力，把可选部分拆成单独 bean，而不是让核心 service 整体消失。

如果 mapper scan 修复后某些 mapper 丢失，应把 mapper 移到清晰的 `persistence`、`store`、`mapper` 包，或用 `basePackageClasses` 精确标记 mapper 类所在包。不要扩大到模块根包。

## Artifacts and Notes

关键现状证据。计划开始时只有 file 等部分模块有 imports，storage 和 mq 缺 imports。当前工作树中，自动配置 imports 已经扩展到以下代表性模块：

    pixflow-module-file/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      -> com.pixflow.module.file.config.FileAutoConfiguration

    pixflow-infra-storage/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      -> com.pixflow.infra.storage.config.StorageAutoConfiguration

    pixflow-infra-mq/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      -> com.pixflow.infra.mq.config.MqAutoConfiguration

    pixflow-permission/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      -> com.pixflow.harness.permission.config.PermissionAutoConfiguration

    pixflow-tools/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
      -> com.pixflow.harness.tools.config.ToolsAutoConfiguration

    pixflow-app/src/main/java/com/pixflow/app/PixFlowApplication.java
      -> app 启动类位于 com.pixflow.app，只扫描 app 层；显式 import HttpErrorRenderer

    pixflow-module-file/src/main/java/com/pixflow/module/file/config/FileAutoConfiguration.java
      -> 创建 FileService、FileController、SourceImageReader

    pixflow-module-file/src/main/java/com/pixflow/module/file/image/DefaultSourceImageReader.java
      -> file 模块实现 imagegen 的 SourceImageReader SPI

本轮已经运行并通过的验证：

    mvn -pl pixflow-module-file -am test
    mvn -pl pixflow-module-commerce -am test
    mvn -pl pixflow-permission -am test
    mvn -pl pixflow-tools -am -DskipTests compile

较早阶段曾通过：

    mvn -pl pixflow-app -am -DskipTests clean package

当前待重新运行的最终验证：

    mvn -pl pixflow-app -am -DskipTests install
    mvn -pl pixflow-app spring-boot:run "-Dspring-boot.run.profiles=dev" "-Dspring-boot.run.arguments=--debug"

推荐的最终装配模型仍然是：

    pixflow-app
      只扫描 com.pixflow.app
      通过 Spring Boot 自动配置导入 infra / harness / module / agent

    pixflow-infra-storage
      AutoConfiguration.imports -> StorageAutoConfiguration
      StorageAutoConfiguration 创建 ObjectStorage

    pixflow-infra-mq
      AutoConfiguration.imports -> MqAutoConfiguration
      MqAutoConfiguration 创建 MessagePublisher

    pixflow-module-file
      AutoConfiguration.imports -> FileAutoConfiguration
      FileAutoConfiguration after storage + mq + mybatis
      FileAutoConfiguration 创建 FileService 与 FileController

## Interfaces and Dependencies

每个可复用模块必须暴露一个清晰的自动配置入口。文件路径固定为：

    <module>/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports

文件内容是自动配置类全限定名，每行一个，例如：

    com.pixflow.infra.storage.config.StorageAutoConfiguration

配置类必须使用：

    org.springframework.boot.autoconfigure.AutoConfiguration

如果需要排序，使用：

    @AutoConfiguration(after = {SomeOtherAutoConfiguration.class})

或在无法引用类时使用：

    @AutoConfigureAfter(name = "fully.qualified.ClassName")

模块 controller 的推荐最终接口形态是普通 Java 类，不带 `@RestController`：

    public class FileController {
        public FileController(FileService fileService) { ... }
    }

由模块自动配置暴露：

    @Bean
    @ConditionalOnMissingBean
    public FileController fileController(FileService fileService) { ... }

当前工作树允许一个中间状态：controller 类仍保留 `@RestController`，但 app 启动类位于 `com.pixflow.app`，不会扫描 `com.pixflow.module.*`，因此这些注解不会再导致模块 controller 通过根包扫描进入容器。只要 controller bean 是由模块自动配置创建，装配边界仍满足本计划目标。后续若移除这些注解，必须先确认 MVC mapping 仍由自动配置 bean 正常暴露。

核心业务 service 不应整体被 `@ConditionalOnBean` 隐藏。它的必需依赖应作为方法参数直接声明：

    @Bean
    @ConditionalOnMissingBean
    public FileService fileService(
        AssetPackageService packageService,
        AssetPackageMapper packageMapper,
        AssetIngestErrorMapper errorMapper,
        AssetCopyMapper copyMapper,
        CsvCopyDocParser csvCopyDocParser,
        ExcelCopyDocParser excelCopyDocParser,
        ObjectStorage objectStorage,
        ExtractionPublisher extractionPublisher
    ) { ... }

可选 worker 或 listener 可以继续用 `@ConditionalOnBean`：

    @Bean
    @ConditionalOnBean({ManagedListenerContainerFactory.class, ExtractionConsumer.class, ExtractionErrorHandler.class})
    public ManagedMessageContainer fileExtractionListenerContainer(...) { ... }

mapper 扫描必须只覆盖 mapper 包。推荐形态：

    @MapperScan(
        basePackageClasses = {AssetPackageMapper.class, AssetImageMapper.class},
        annotationClass = Mapper.class
    )

不允许把 `@MapperScan` 指向 `com.pixflow.module.<name>` 根包。

## Revision Notes

2026-07-03 / Codex: 新建本 ExecPlan。计划针对 `FileController` 找不到 `FileService` 暴露出的系统性装配问题，提出统一 Spring Boot AutoConfiguration imports、收窄 app 组件扫描、controller 跟随 service 装配、核心门面不再被条件静默隐藏、mapper scan 精确化和自动配置测试守护的重构方案。该方案避免用 `@Service` 注解掩盖症状，目标是彻底消除模块 Bean 装配顺序不可靠的问题。

2026-07-03 / Codex: 按当前实现进度更新本 ExecPlan。记录已补齐的自动配置 imports、app 启动类迁移、controller 自动配置 bean、mapper scan 收窄、file/imagegen SPI、permission 自动配置、tools 默认 `PlanModeView` 和 permission 字节码签名修复；同时记录已通过的模块验证与尚未完成的最终 app install/dev startup 验证。此次更新的原因是用户要求“按照现在做到的进度更新计划文档并记录”，因此只更新计划文档，不继续扩大代码重构范围。

2026-07-03 / Codex: 执行最终闭环与测试补强。新增 file 自动配置、commerce mapper 扫描边界、app 扫描边界三类守护测试；移除 commerce 核心服务链上导致 `CommerceService` 被静默跳过的条件装配；重新运行 file、commerce、app、tools 与 app install 验证；dev profile 启动推进到 Redis 连接拒绝，说明当前失败已是外部依赖未启动而非 Spring 自动配置边界问题。

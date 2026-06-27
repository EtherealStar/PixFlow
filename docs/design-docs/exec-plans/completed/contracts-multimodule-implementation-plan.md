# contracts 模块与 Maven 多模块改造实施计划

本 ExecPlan 必须按照仓库根目录的 `PLANS.md` 维护。它是活文档，后续任何实现者推进、修正或完成本计划时，都必须同步更新 `Progress`、`Surprises & Discoveries`、`Decision Log`、`Outcomes & Retrospective`，并在文末记录修改原因。

## Purpose / Big Picture

完成本计划后，PixFlow 会从当前“单 Maven artifact、按包约定分层”的状态，升级为真正的 Maven 多模块工程。`contracts` 会成为独立的 `pixflow-contracts` Maven 子模块，只包含确认令牌相关的纯契约类型，并且在编译期看不见 `common`、`permission`、`infra`、`module`、`agent` 这些上层或横切模块。

这件事的用户价值不是页面上多一个功能，而是为后续 `infra/cache` 的 Redis 确认令牌实现扫清依赖环：`permission` 可以依赖 `contracts.ConfirmationTokenStore` 做令牌校验，`infra/cache` 可以依赖同一个 SPI 提供 Redis 实现，二者不再互相依赖。实现完成后，可以通过 `mvn test` 观察到整个 reactor 按模块顺序编译并测试通过；也可以通过查看 `pixflow-contracts/pom.xml` 和 Maven dependency tree 证明 contracts 没有 Spring、Jackson、Lombok 或任何 PixFlow 内部依赖。

本计划按用户要求采取完整多模块大改，不为了兼容性保留无用旧代码。迁移时删除旧的 `com.pixflow.harness.permission.token` 纯契约类型，不创建 deprecated wrapper、桥接类或双包并存路径。

## Progress

- [x] (2026-06-27 18:20+08:00) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/design.md`、`docs/design-docs/module/contracts.md`、`docs/design-docs/module/permission.md`、`docs/design-docs/module/cache.md`、`docs/design-docs/exec-plans/module-dependency-dag-plan.md`。
- [x] (2026-06-27 18:20+08:00) 确认用户要求直接做完整 Maven 多模块改造，并明确不要为了兼容性保留旧 token 包代码。
- [x] (2026-06-27 18:20+08:00) 检查当前源码结构，确认现有 `common`、`harness/permission`、`infra/storage`、`infra/mq` 都仍位于根工程 `src/main/java` 下，确认令牌契约当前位于 `src/main/java/com/pixflow/harness/permission/token`。
- [x] (2026-06-27 18:20+08:00) 新增本 ExecPlan，定义完整多模块目标结构、迁移机制、验证命令和设计文档快速定位关键词。
- [x] (2026-06-27 16:15+08:00) 将根 `pom.xml` 改为 Maven reactor 父 POM，并创建 `pixflow-contracts`、`pixflow-common`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-app` 子模块。
- [x] (2026-06-27 16:20+08:00) 移动现有源码和测试到对应子模块，不保留旧根目录 `src/main/java` 下的业务代码。
- [x] (2026-06-27 16:22+08:00) 将确认令牌纯契约移动到 `com.pixflow.contracts.confirmation`，删除旧 `com.pixflow.harness.permission.token` 中对应类型。
- [x] (2026-06-27 16:22+08:00) 更新 permission 与测试 imports，使它们依赖 `pixflow-contracts`；`ConfirmationTokenService` 保持在 permission 行为服务包，`InMemoryConfirmationTokenStore` 移入测试源码。
- [x] (2026-06-27 16:23+08:00) 为 `pixflow-contracts` 配置 Maven Enforcer 约束，禁止任何内部模块和重型框架依赖。
- [x] (2026-06-27 16:30+08:00) 运行 `mvn test` 验证 reactor 全量构建通过；结果为 41 个测试执行、2 个 MinIO 集成测试按环境变量跳过，BUILD SUCCESS。

## Surprises & Discoveries

- Observation: 用户给出的 `.kiro/specs/pixflow/design.md` 在当前工作区不存在，当前完整重写阶段的总设计文档实际位于 `docs/design-docs/design.md`。
  Evidence: 执行 `rg --files -g design.md` 只返回 `docs/design-docs/design.md`。

- Observation: 当前代码已经实现了确认令牌的大部分行为，但物理位置不符合 contracts 设计。
  Evidence: `rg --files src/main/java/com/pixflow | rg "Confirmation|TokenClaims|TokenStore"` 显示 `ConfirmationToken`、`TokenClaims`、`ConfirmationAction`、`ConfirmationLevel`、`ConfirmationTokenStore` 位于 `src/main/java/com/pixflow/harness/permission/token`。

- Observation: 当前工程已经出现多个实际模块的源码，但它们都混在单 artifact 的根 `src` 下。
  Evidence: `rg --files src/main/java/com/pixflow` 显示 `common`、`harness/permission`、`infra/storage`、`infra/mq` 和 `PixFlowApplication.java` 同处一个 Maven 工程。

- Observation: 工作区已有多处未提交改动，实施本计划时必须只移动和修改本计划相关文件，不回滚用户已有改动。
  Evidence: `git status --short` 显示 `docs/design-docs/design.md`、`docs/design-docs/module/permission.md`、`pom.xml` 等已有修改，并存在新增的 `contracts.md`、`cache.md` 等文档。

- Observation: 拆分模块后，`common` 需要显式声明 servlet API，`infra/mq` 需要显式声明 Jackson databind。
  Evidence: 首次运行 `mvn -pl pixflow-common test` 报缺少 `jakarta.servlet.ServletException`；首次运行 `mvn -pl pixflow-infra-mq -am test` 报缺少 `com.fasterxml.jackson.databind.ObjectMapper`。分别补充 `jakarta.servlet:jakarta.servlet-api` 与 `com.fasterxml.jackson.core:jackson-databind` 后测试通过。

- Observation: `pixflow-contracts` 的 Maven Enforcer 已在 validate 阶段生效。
  Evidence: `mvn -pl pixflow-contracts test` 输出 `Rule 0: org.apache.maven.enforcer.rules.dependency.BannedDependencies passed`，并成功编译 5 个契约源码文件。

## Decision Log

- Decision: 采用真正 Maven 多模块 reactor，而不是先做 `com.pixflow.contracts` 包级过渡。
  Rationale: 用户明确要求“直接开始做完整多模块大改”，且 `contracts.md` 的 Option A 指出独立 Maven 模块才能让编译器保证 contracts 看不见上层类型。
  Date/Author: 2026-06-27 / Codex

- Decision: 不保留旧 `com.pixflow.harness.permission.token` 契约类型，也不创建 deprecated wrapper。
  Rationale: 用户明确要求不要为了兼容性保持没用的旧代码；双包并存会让后续 `infra/cache` 和 `permission` 可能继续误用旧包，削弱 contracts 的唯一入口地位。
  Date/Author: 2026-06-27 / Codex

- Decision: 第一版 reactor 只拆出现有代码已经对应的六个模块：`pixflow-contracts`、`pixflow-common`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-app`。
  Rationale: 总设计还有 `dag`、`task`、`ai`、`vector` 等未来模块，但当前源码不存在这些实现。为不存在的模块预建空 Maven 子模块会增加构建噪声，无法产生可观察行为。后续模块在各自计划中新增。
  Date/Author: 2026-06-27 / Codex

- Decision: `pixflow-contracts` 只依赖 JDK，不依赖 `pixflow-common`。
  Rationale: `contracts.md` 明确 contracts 是零依赖叶子；如果它依赖 common，就会把错误模型、Spring 渲染器等横切行为引入契约层，破坏“只放形状”的设计原则。
  Date/Author: 2026-06-27 / Codex

- Decision: `InMemoryConfirmationTokenStore` 不进入 `pixflow-contracts`。
  Rationale: 它是测试替身，有行为和时钟依赖，只服务 permission 测试；contracts 只定义 `ConfirmationTokenStore` SPI，不提供实现。
  Date/Author: 2026-06-27 / Codex

## Outcomes & Retrospective

本计划已完成当前仓库内可执行的 Maven 多模块改造。根工程现在是 reactor 父 POM，包含 `pixflow-contracts`、`pixflow-common`、`pixflow-permission`、`pixflow-infra-storage`、`pixflow-infra-mq`、`pixflow-app` 六个子模块。`mvn test` 从根目录编译并测试整个 reactor，构建顺序中 `pixflow-contracts` 早于 `pixflow-permission`，符合依赖 DAG。

确认令牌的纯契约类型已经只存在于 `pixflow-contracts/src/main/java/com/pixflow/contracts/confirmation`。旧 `com.pixflow.harness.permission.token` 主源码包中不再定义 `ConfirmationToken`、`TokenClaims`、`ConfirmationAction`、`ConfirmationLevel`、`ConfirmationTokenStore`；该包只保留 permission 自己的 `ConfirmationTokenService` 行为服务。测试替身 `InMemoryConfirmationTokenStore` 已移入 `pixflow-permission/src/test/java`。

验证结果：

- `mvn -pl pixflow-contracts test`：BUILD SUCCESS，contracts 无测试，Enforcer 通过。
- `mvn -pl pixflow-common test`：BUILD SUCCESS，14 个测试通过。
- `mvn -pl pixflow-permission -am test`：BUILD SUCCESS，20 个测试通过（含上游 common 14 个、permission 6 个）。
- `mvn -pl pixflow-infra-storage -am test`：BUILD SUCCESS，13 个测试执行，其中 2 个 MinIO 集成测试按环境变量跳过。
- `mvn -pl pixflow-infra-mq -am test`：BUILD SUCCESS，22 个测试通过（含上游 common 14 个、mq 8 个）。
- `mvn test`：BUILD SUCCESS，reactor 全量 41 个测试执行，2 个 MinIO 集成测试按环境变量跳过。

## Context and Orientation

本仓库是 PixFlow 电商运营 Agent。当前开发阶段是完整重写，不以 MVP 结构为约束。总设计位于 `docs/design-docs/design.md`，模块依赖顺序位于 `docs/design-docs/exec-plans/module-dependency-dag-plan.md`，contracts 模块权威设计位于 `docs/design-docs/module/contracts.md`。

“Maven reactor” 指一个父 `pom.xml` 聚合多个 Maven 子模块。执行者在仓库根目录运行 `mvn test` 时，Maven 会根据各子模块的依赖关系决定编译顺序。例如 `pixflow-permission` 依赖 `pixflow-contracts`，所以 contracts 会先编译。这个机制比“同一个 artifact 里靠包名约定”更强，因为 contracts 子模块的 classpath 里根本没有 permission 或 infra 的类，违规 import 会在编译期失败。

“contracts” 在本计划中特指跨模块共享的纯契约模块。纯契约是指 interface、record、enum 和常量，不包含 Spring Bean、Redis/MySQL 实现、序列化配置、业务服务或流程逻辑。当前只允许确认令牌这一组契约进入 contracts，因为它用于打破 `permission` 和 `infra/cache` 的依赖环。

当前源码仍是单 Maven artifact。根目录 `pom.xml` 是 Spring Boot 应用 POM，所有代码位于根 `src` 下。重要现状如下：

- `src/main/java/com/pixflow/PixFlowApplication.java` 是 Spring Boot 启动类。
- `src/main/java/com/pixflow/common` 是 common 模块源码。
- `src/main/java/com/pixflow/harness/permission` 是 permission 模块源码。
- `src/main/java/com/pixflow/harness/permission/token` 当前混放了本该进 contracts 的纯契约类型，以及应留在 permission 的 `ConfirmationTokenService` 和测试替身。
- `src/main/java/com/pixflow/infra/storage` 是 storage 模块源码。
- `src/main/java/com/pixflow/infra/mq` 是 mq 模块源码。
- `src/test/java/com/pixflow/...` 下有对应测试。

设计文档快速定位关键词如下。阅读 `docs/design-docs/module/contracts.md` 时，搜索 `Option A` 可以定位为什么 contracts 必须是真正 Maven 模块；搜索 `零依赖叶子` 可以定位 contracts 不依赖 common 的硬要求；搜索 `确认令牌契约` 可以定位 5 个目标类型；搜索 `准入准则` 可以定位什么类型不能放进 contracts；搜索 `从现状迁移` 可以定位旧包迁移到新包的目标。

阅读 `docs/design-docs/design.md` 时，搜索 `共享契约独立成模块` 可以定位总体设计原则；搜索 `业务模块划分` 可以定位 contracts、common、harness、infra、module、agent 的分层；搜索 `HITL 确认` 可以定位确认令牌在用户确认流程中的作用；搜索 `Redis（键约定）` 或 `确认令牌类型` 可以定位 Redis 运行时键与令牌契约的边界。

阅读 `docs/design-docs/exec-plans/module-dependency-dag-plan.md` 时，搜索 `Wave 0 地基` 可以定位 contracts 的实施波次；搜索 `contracts 前置` 可以定位为什么它是关键路径起点；搜索 `模块依赖 DAG` 可以查看 contracts 到 permission 和 cache 的依赖方向。

阅读 `docs/design-docs/module/permission.md` 时，搜索 `令牌走可信上下文通道` 可以定位 token 不进入 LLM tool input 的安全边界；搜索 `令牌与动作载荷强绑定` 可以定位 `TokenClaims` 字段语义；搜索 `verifyAndConsume` 可以定位 permission 应保留的行为逻辑；搜索 `令牌契约来自 contracts` 可以定位哪些类型要移出 permission。

阅读 `docs/design-docs/module/cache.md` 时，搜索 `RedisConfirmationTokenStore` 可以定位 infra/cache 后续如何实现 `ConfirmationTokenStore`；搜索 `确认令牌存储` 可以定位 Redis TTL 和原子 consume 测试；搜索 `infra/cache → common + contracts` 可以定位 cache 对 contracts 的依赖关系。

## Architecture and Mechanism

目标架构是“父 POM 统一版本和构建规则，子模块各自声明自己的真实依赖”。根目录 `pom.xml` 改为 `packaging=pom`，只做聚合、版本管理和插件管理，不再直接编译 Java 代码。Spring Boot 可执行应用放入 `pixflow-app` 子模块，只有这个模块使用 Spring Boot Maven Plugin 打包成可运行 jar。

第一版 reactor 目标结构如下：

    D:\study\PixFlow
    ├── pom.xml                         父 POM，packaging=pom
    ├── pixflow-contracts
    │   ├── pom.xml
    │   └── src/main/java/com/pixflow/contracts/confirmation
    ├── pixflow-common
    │   ├── pom.xml
    │   └── src/main/java/com/pixflow/common
    ├── pixflow-permission
    │   ├── pom.xml
    │   └── src/main/java/com/pixflow/harness/permission
    ├── pixflow-infra-storage
    │   ├── pom.xml
    │   └── src/main/java/com/pixflow/infra/storage
    ├── pixflow-infra-mq
    │   ├── pom.xml
    │   └── src/main/java/com/pixflow/infra/mq
    └── pixflow-app
        ├── pom.xml
        └── src/main/java/com/pixflow/PixFlowApplication.java

`pixflow-contracts` 是最底层模块。它只包含 `com.pixflow.contracts.confirmation` 包下的 5 个类型。它的 POM 不声明 Spring、Jackson、Lombok、common 或任何 PixFlow 内部模块依赖。它可以继承父 POM 的 Java 17 编译配置，但不能继承会把 Spring Boot 应用打包逻辑绑定到自身的插件执行。

`pixflow-common` 是横切通用模块，承载错误模型、响应信封、分页、脱敏和错误渲染。它可以依赖 Spring Web、Micrometer 等 common 现有代码真正需要的库，但不能依赖 contracts。common 和 contracts 是并列地基，彼此独立。

`pixflow-permission` 依赖 `pixflow-common` 和 `pixflow-contracts`。`ConfirmationTokenService` 留在 permission，因为它有签发、校验、消费、抛 permission 错误等行为。`PermissionContext` 和 `PermissionSubject` 改为引用 `com.pixflow.contracts.confirmation.ConfirmationToken` 和 `ConfirmationAction`。`InMemoryConfirmationTokenStore` 移到 `pixflow-permission/src/test/java`，只作为 permission 测试使用。

`pixflow-infra-storage` 依赖 `pixflow-common` 和 MinIO 等 storage 所需库。它不依赖 contracts，因为当前 storage 与确认令牌无关。它继续只提供对象存储抽象和 MinIO 实现。

`pixflow-infra-mq` 依赖 `pixflow-common` 和 Spring AMQP、Micrometer 等 MQ 所需库。它不依赖 contracts，也不依赖 permission 或 task。它继续保持领域无关消息设施。

`pixflow-app` 是应用装配模块，依赖上述所有已实现功能模块，并保留 `PixFlowApplication`。如果后续需要运行服务或打包，执行者应该在这个模块上运行 Spring Boot 插件；如果只需要验证全部模块，根目录 `mvn test` 即可。

依赖方向应固定为：

    pixflow-contracts
    pixflow-common
    pixflow-permission       -> pixflow-common + pixflow-contracts
    pixflow-infra-storage    -> pixflow-common
    pixflow-infra-mq         -> pixflow-common
    pixflow-app              -> pixflow-common + pixflow-contracts + pixflow-permission + pixflow-infra-storage + pixflow-infra-mq

确认令牌机制保持现有业务语义，但换成独立契约入口。`ConfirmationTokenStore` 是 SPI，也就是“由调用方依赖接口、由基础设施提供实现”的接缝。permission 只知道 `ConfirmationTokenStore.save` 和 `ConfirmationTokenStore.consume`；后续 `infra/cache` 会实现 Redis 版本，使用 Lua 或等价机制保证 `consume` 是原子读取加删除。contracts 本身不实现 Redis，不处理异常归一化，不做 Spring 装配。

## Milestones

第一个里程碑是建立 Maven reactor 骨架。结束时，根 `pom.xml` 是父 POM，六个子模块都有自己的 `pom.xml`，但源码可以先不移动或只移动最小启动类。验证方式是在根目录运行 `mvn -q -DskipTests validate`，预期 Maven 能识别所有模块并完成 validate 阶段。

第二个里程碑是迁移 `pixflow-contracts`。结束时，5 个确认令牌契约类型只存在于 `pixflow-contracts/src/main/java/com/pixflow/contracts/confirmation`，旧 `src/main/java/com/pixflow/harness/permission/token` 下不再保留这些契约类型。验证方式是运行 `mvn -pl pixflow-contracts test`，预期 contracts 模块独立编译通过，并且 dependency tree 中没有任何 compile/runtime 依赖。

第三个里程碑是迁移 common 和 permission。结束时，common 源码进入 `pixflow-common`，permission 源码进入 `pixflow-permission`，permission 通过 Maven 依赖 contracts 和 common，不再通过同 artifact 包路径访问令牌类型。验证方式是运行 `mvn -pl pixflow-permission -am test`，预期 permission 测试通过，且 imports 指向 `com.pixflow.contracts.confirmation`。

第四个里程碑是迁移现有 infra 模块。结束时，storage 和 mq 分别进入 `pixflow-infra-storage` 与 `pixflow-infra-mq`，它们只依赖 common 和各自外部库。验证方式是分别运行 `mvn -pl pixflow-infra-storage -am test` 与 `mvn -pl pixflow-infra-mq -am test`。MinIO 集成测试如果仍按环境变量跳过，测试输出应明确显示 skipped，而不是声称已通过真实容器测试。

第五个里程碑是迁移 app 并清理旧根源码。结束时，`PixFlowApplication` 位于 `pixflow-app`，根目录不再有旧的 `src/main/java` 业务代码。验证方式是在根目录运行 `mvn test`，预期 reactor 全量测试通过。随后运行 `rg "com.pixflow.harness.permission.token" .`，预期没有输出。

第六个里程碑是加依赖约束守护。结束时，`pixflow-contracts` 的 POM 使用 Maven Enforcer 禁止 `com.pixflow:*`、`org.springframework:*`、`com.fasterxml.jackson*:*`、`org.projectlombok:lombok` 等依赖进入 contracts。验证方式是运行 `mvn -pl pixflow-contracts validate`，预期通过；如果有人向 contracts 加 Spring 依赖，该命令应失败。

## Plan of Work

先修改根 `pom.xml`。保留现有 groupId、version、Java 17、Spring Boot 版本和现有依赖版本属性，把根 POM 改成 `packaging=pom`。将原来根 POM 的外部依赖拆到各子模块 POM 中，根 POM 只保留 `dependencyManagement`、`pluginManagement`、公共 properties、repositories 和 `<modules>`。不要让根 POM 直接声明所有 runtime dependencies，否则会掩盖子模块依赖边界。

然后创建 `pixflow-contracts/pom.xml`。该模块 artifactId 为 `pixflow-contracts`，packaging 默认为 jar。它继承父 POM 的 Java 17 编译配置，但不声明任何 dependencies。将现有 `ConfirmationToken`、`TokenClaims`、`ConfirmationAction`、`ConfirmationLevel`、`ConfirmationTokenStore` 移入 `pixflow-contracts/src/main/java/com/pixflow/contracts/confirmation`，包名改为 `com.pixflow.contracts.confirmation`。保留 record compact constructor 中的值对象自洽校验，例如 tokenId 非空、expectedCount 非负、expiresAt 晚于 issuedAt。不要引入 Lombok、Jackson 注解或 Spring 注解。

接着创建 `pixflow-common/pom.xml`，移动 `src/main/java/com/pixflow/common` 和 `src/test/java/com/pixflow/common`。common POM 只声明 common 源码真正需要的依赖，例如 Spring Web 或 Micrometer，不声明 storage、mq、MinIO、RabbitMQ、contracts 或 permission。移动后运行 common 模块测试，若发现缺少依赖，以编译错误为准补充最小依赖。

然后创建 `pixflow-permission/pom.xml`，移动 `src/main/java/com/pixflow/harness/permission` 和 `src/test/java/com/pixflow/harness/permission`。移动时删除旧 token 包中的 5 个契约类型，只保留并调整 `ConfirmationTokenService`。建议把 `ConfirmationTokenService` 放在 `com.pixflow.harness.permission.token` 仍可接受，因为它是 permission 内部的 token 行为服务；但该包下不能再定义 contracts 已拥有的纯契约类型。`InMemoryConfirmationTokenStore` 移到 `pixflow-permission/src/test/java/com/pixflow/harness/permission/token`。更新所有 imports，从 `com.pixflow.harness.permission.token.ConfirmationAction` 等改为 `com.pixflow.contracts.confirmation.ConfirmationAction`。

再创建 `pixflow-infra-storage/pom.xml`，移动 `src/main/java/com/pixflow/infra/storage` 和 `src/test/java/com/pixflow/infra/storage`。storage POM 声明 `pixflow-common`、MinIO、Spring Boot 配置所需依赖、Testcontainers 测试依赖。storage 不能依赖 permission、contracts、mq 或 app。

随后创建 `pixflow-infra-mq/pom.xml`，移动 `src/main/java/com/pixflow/infra/mq` 和 `src/test/java/com/pixflow/infra/mq`。mq POM 声明 `pixflow-common`、Spring AMQP、Micrometer、测试依赖。mq 不能依赖 permission、contracts、storage 或 app。

最后创建 `pixflow-app/pom.xml`，移动 `src/main/java/com/pixflow/PixFlowApplication.java`。app POM 声明 Spring Boot starter 和各已实现 PixFlow 模块依赖，并配置 Spring Boot Maven Plugin。根目录旧 `src` 如果变空，应删除；如果仍有未来未归属代码，必须先分类到对应模块，不能把根 `src` 留作隐形第七模块。

完成源码移动后，全局搜索旧包和非法依赖。`com.pixflow.harness.permission.token.ConfirmationToken` 等旧路径不应再出现。`pixflow-contracts` 源码中不应 import `com.pixflow.common`、`org.springframework`、`com.fasterxml.jackson` 或 `lombok`。如果出现，说明迁移破坏了 contracts 的零依赖叶子原则，应立即修正，而不是在 POM 中补依赖。

## Concrete Steps

从仓库根目录开始：

    cd D:\study\PixFlow
    git status --short

如果看到用户已有改动，不要回滚。只移动和编辑本计划范围内的 Maven 文件、Java 源码和对应测试。当前已知存在未提交文档和 POM 改动，实施者必须在最终说明中区分“本计划新增/修改”和“原本已存在”。

先确认当前源码入口：

    cd D:\study\PixFlow
    rg --files src/main/java/com/pixflow
    rg --files src/test/java/com/pixflow

预期能看到 `common`、`harness/permission`、`infra/storage`、`infra/mq` 和 `PixFlowApplication.java`。如果未来执行时源码已经被部分迁移，应先更新本计划的 `Progress` 和 `Surprises & Discoveries`，说明现状差异，再继续。

创建子模块目录。实施者可以用 IDE 移动文件，也可以用 PowerShell `Move-Item`，但每次移动前后都要用 `rg --files` 核对目标。建议移动映射如下：

    src/main/java/com/pixflow/common
      -> pixflow-common/src/main/java/com/pixflow/common

    src/test/java/com/pixflow/common
      -> pixflow-common/src/test/java/com/pixflow/common

    src/main/java/com/pixflow/harness/permission
      -> pixflow-permission/src/main/java/com/pixflow/harness/permission

    src/test/java/com/pixflow/harness/permission
      -> pixflow-permission/src/test/java/com/pixflow/harness/permission

    src/main/java/com/pixflow/infra/storage
      -> pixflow-infra-storage/src/main/java/com/pixflow/infra/storage

    src/test/java/com/pixflow/infra/storage
      -> pixflow-infra-storage/src/test/java/com/pixflow/infra/storage

    src/main/java/com/pixflow/infra/mq
      -> pixflow-infra-mq/src/main/java/com/pixflow/infra/mq

    src/test/java/com/pixflow/infra/mq
      -> pixflow-infra-mq/src/test/java/com/pixflow/infra/mq

    src/main/java/com/pixflow/PixFlowApplication.java
      -> pixflow-app/src/main/java/com/pixflow/PixFlowApplication.java

迁移 contracts 时，不是复制后保留旧代码，而是移动和改包名。目标文件为：

    pixflow-contracts/src/main/java/com/pixflow/contracts/confirmation/ConfirmationToken.java
    pixflow-contracts/src/main/java/com/pixflow/contracts/confirmation/TokenClaims.java
    pixflow-contracts/src/main/java/com/pixflow/contracts/confirmation/ConfirmationAction.java
    pixflow-contracts/src/main/java/com/pixflow/contracts/confirmation/ConfirmationLevel.java
    pixflow-contracts/src/main/java/com/pixflow/contracts/confirmation/ConfirmationTokenStore.java

移动后更新 imports：

    cd D:\study\PixFlow
    rg "com\.pixflow\.harness\.permission\.token\.(ConfirmationToken|TokenClaims|ConfirmationAction|ConfirmationLevel|ConfirmationTokenStore)"

预期在完成迁移后没有输出。`ConfirmationTokenService` 的包可以仍是 `com.pixflow.harness.permission.token`，但它 import 的 token、claims、store 类型必须来自 `com.pixflow.contracts.confirmation`。

每完成一个模块，运行该模块测试。命令如下：

    cd D:\study\PixFlow
    mvn -pl pixflow-contracts test
    mvn -pl pixflow-common test
    mvn -pl pixflow-permission -am test
    mvn -pl pixflow-infra-storage -am test
    mvn -pl pixflow-infra-mq -am test
    mvn test

`-pl` 表示只构建指定模块，`-am` 表示同时构建它依赖的上游模块。最终 `mvn test` 必须在根目录执行，证明整个 reactor 能作为一个整体工作。

验证 contracts 依赖边界：

    cd D:\study\PixFlow
    mvn -pl pixflow-contracts dependency:tree

预期输出中 `pixflow-contracts` 不应列出 compile 或 runtime 依赖。如果 Maven 插件输出包含测试插件自身依赖，不要误判；重点看项目 dependencies 区域。也可以运行：

    cd D:\study\PixFlow
    rg "import (com\.pixflow\.common|com\.pixflow\.harness|com\.pixflow\.infra|com\.pixflow\.module|com\.pixflow\.agent|org\.springframework|com\.fasterxml|lombok)" pixflow-contracts

预期没有输出。

最终清理旧根源码：

    cd D:\study\PixFlow
    rg --files src

如果 `src` 目录仍存在且有业务代码，说明迁移未完成。根工程作为父 POM 不应继续编译根 `src`。如果 `src` 已空，可以删除空目录；如果 Windows 或 Git 不跟踪空目录，可以不特别处理，但不能留下 Java 源文件。

## Validation and Acceptance

第一层验收是 Maven reactor 可用。在仓库根目录运行 `mvn test`，预期 Maven 输出包含多个子模块，并最终显示 `BUILD SUCCESS`。如果测试数量变化，应记录真实数量。当前已有 MinIO 集成测试可能因未设置 `PIXFLOW_MINIO_INTEGRATION=true` 被跳过；这种跳过可以接受，但最终说明必须写清楚“集成测试按条件跳过”，不能写成真实 MinIO 集成测试通过。

第二层验收是 contracts 真正独立。运行 `mvn -pl pixflow-contracts test` 应通过；运行 `mvn -pl pixflow-contracts dependency:tree` 时不应看到 Spring、Jackson、Lombok、common、permission、storage、mq 等依赖。向 `pixflow-contracts/pom.xml` 临时加入一个 Spring 依赖时，`mvn -pl pixflow-contracts validate` 应被 Maven Enforcer 拦截。这个临时破坏测试只用于人工验证，不要提交破坏性改动。

第三层验收是旧 token 包契约被彻底移除。运行：

    cd D:\study\PixFlow
    rg "com\.pixflow\.harness\.permission\.token\.(ConfirmationToken|TokenClaims|ConfirmationAction|ConfirmationLevel|ConfirmationTokenStore)"

预期没有输出。运行：

    cd D:\study\PixFlow
    rg "package com\.pixflow\.harness\.permission\.token;" pixflow-permission/src/main/java

可以仍看到 `ConfirmationTokenService`，但不能看到 `ConfirmationToken`、`TokenClaims`、`ConfirmationAction`、`ConfirmationLevel`、`ConfirmationTokenStore` 这些契约类型。

第四层验收是 permission 行为不变。运行 `mvn -pl pixflow-permission -am test`，预期 `PermissionPolicyTest` 仍覆盖：无 token 返回 `CONFIRM_REQUIRED`，有效 token `ALLOW` 且只能消费一次，payload mismatch 返回 `DENY`，NORMAL token 不能通过 bulk 阈值，vision 只读子 Agent 不能执行副作用动作。测试通过说明移动 contracts 没有改变 HITL 安全语义。

第五层验收是模块依赖方向正确。`pixflow-permission/pom.xml` 必须依赖 `pixflow-common` 和 `pixflow-contracts`。`pixflow-infra-storage/pom.xml` 和 `pixflow-infra-mq/pom.xml` 不能依赖 permission。`pixflow-common/pom.xml` 不能依赖 contracts。`pixflow-app/pom.xml` 可以依赖所有已实现模块，因为它是装配层。

## Idempotence and Recovery

本计划涉及大量文件移动，实施前不要执行 `git reset --hard` 或 `git checkout --` 这类会丢弃用户改动的命令。每完成一个模块移动后，运行该模块测试；如果失败，优先根据编译错误补齐该模块 POM 的最小依赖或修正 import，不要把源码搬回根目录作为绕过。

如果迁移中断，恢复方式是从 `git status --short` 和 `rg --files` 判断哪些包已经移动。已经移动到目标模块的源码不要重复复制；缺失的源码按上文映射继续移动。因为本计划不保留旧代码，若同一个类型在旧路径和新路径都存在，应保留新路径，删除旧路径，并更新 imports。

如果 Maven reactor 已创建但某个子模块测试失败，可以先运行更小范围命令定位：

    mvn -pl pixflow-contracts test
    mvn -pl pixflow-common test
    mvn -pl pixflow-permission -am test

从最底层开始修复。不要先跑 app 模块掩盖底层边界问题。contracts 一旦需要外部依赖才能编译，通常说明类型放错了模块，应把该类型移回属主模块，而不是给 contracts 加依赖。

如果因为本地 Maven 需要下载插件或依赖而失败，记录错误输出。若错误是网络或仓库访问问题，应按当前环境权限要求重新请求网络执行；不要把未下载成功解释为代码失败。

## Artifacts and Notes

关键设计产物是以下目标包和模块：

    pixflow-contracts
      com.pixflow.contracts.confirmation.ConfirmationToken
      com.pixflow.contracts.confirmation.TokenClaims
      com.pixflow.contracts.confirmation.ConfirmationAction
      com.pixflow.contracts.confirmation.ConfirmationLevel
      com.pixflow.contracts.confirmation.ConfirmationTokenStore

    pixflow-permission
      com.pixflow.harness.permission.token.ConfirmationTokenService
      test-only com.pixflow.harness.permission.token.InMemoryConfirmationTokenStore

完成后，旧的契约包引用应全部消失：

    com.pixflow.harness.permission.token.ConfirmationToken
    com.pixflow.harness.permission.token.TokenClaims
    com.pixflow.harness.permission.token.ConfirmationAction
    com.pixflow.harness.permission.token.ConfirmationLevel
    com.pixflow.harness.permission.token.ConfirmationTokenStore

理想的最终 Maven 输出应类似：

    Reactor Summary for PixFlow 1.0.0-SNAPSHOT:
    pixflow ........................................ SUCCESS
    pixflow-contracts .............................. SUCCESS
    pixflow-common ................................. SUCCESS
    pixflow-permission ............................. SUCCESS
    pixflow-infra-storage .......................... SUCCESS
    pixflow-infra-mq ............................... SUCCESS
    pixflow-app .................................... SUCCESS
    BUILD SUCCESS

实际模块顺序可能因 Maven 依赖排序略有不同，但 contracts 必须早于 permission 编译。

## Interfaces and Dependencies

`pixflow-contracts` 最终必须提供以下 Java API：

    package com.pixflow.contracts.confirmation;

    public record ConfirmationToken(String tokenId) { ... }

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
    ) { ... }

    public enum ConfirmationAction {
        SUBMIT_DAG,
        IMAGEGEN
    }

    public enum ConfirmationLevel {
        NORMAL,
        BULK
    }

    public interface ConfirmationTokenStore {
        void save(String tokenId, TokenClaims claims, Duration ttl);
        Optional<TokenClaims> consume(String tokenId);
    }

`ConfirmationTokenStore.consume` 的语义是原子读取加删除。contracts 只用注释写清这个语义，真实原子性由实现方负责。后续 `pixflow-infra-cache` 的 `RedisConfirmationTokenStore` 必须用 Redis Lua、Redis 事务或等价机制保证并发下只有一次消费成功。

`pixflow-permission` 的 `ConfirmationTokenService` 最终签名应继续表达行为服务：

    public class ConfirmationTokenService {
        public ConfirmationToken issue(TokenClaims claims);
        public void verifyAndConsume(
            ConfirmationToken token,
            PermissionSubject subject,
            PermissionContext context,
            int bulkThreshold
        );
    }

这里的 `ConfirmationToken`、`TokenClaims` 来自 contracts。`PermissionSubject`、`PermissionContext`、`PermissionErrorCode` 仍属于 permission。`verifyAndConsume` 负责检查 action、conversationId、packageId、payloadHash、expectedCount、bulk level 和过期时间，并在失败时抛 permission 错误。

父 POM 管理版本但不声明无差别 runtime dependencies。建议父 POM 保留这些 properties：Java 17、Spring Boot 3.3.0、Spring AI、MyBatis Plus、MinIO、Redisson、Resilience4j、Thumbnailator、TwelveMonkeys、Testcontainers 等。各子模块只声明自己真正使用的依赖。

`pixflow-contracts` 的 Maven Enforcer 规则应在 `validate` 阶段运行。禁止依赖范围至少包括：

    com.pixflow:*
    org.springframework:*
    org.springframework.boot:*
    com.fasterxml.jackson.core:*
    com.fasterxml.jackson.datatype:*
    org.projectlombok:lombok

如果 Enforcer 规则配置为检查 transitive dependencies，也应确保 contracts 没有任何依赖，因此不会误伤正常情况。

## Change Notes

2026-06-27 / Codex: 创建本计划。原因是用户要求直接做完整 Maven 多模块大改，不保留旧兼容代码，并要求按 `PLANS.md` 格式撰写中文计划文档，说明 contracts 模块实现架构、机制，以及可在参考设计文档中快速定位对应文本的搜索关键词。

2026-06-27 / Codex: 执行本计划。原因是用户要求按 `contracts.md` 执行计划、编写代码并加入中文注释；本次完成 Maven reactor、contracts 独立模块、源码迁移、依赖补齐、Enforcer 守护与全量测试验证。

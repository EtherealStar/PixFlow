# 为前后端引入完整的 linter 质量门禁

This ExecPlan is a living document. The sections `Progress`, `Surprises & Discoveries`, `Decision Log`, and `Outcomes & Retrospective` must be kept up to date as work proceeds.

本计划遵循仓库根目录的 `PLANS.md`。执行者只依赖当前工作树和本文，就应能为 PixFlow 引入前端 ESLint、后端 Checkstyle 与 SpotBugs，建立可审阅的存量基线，并证明后续新增违规会阻断对应构建。每个停止点都必须更新本文；修改工具、版本、规则、基线策略、执行顺序或验收命令时，必须同步更新 `Progress`、`Decision Log` 和文末 `Revision Notes`。

## Purpose / Big Picture

完成本计划后，前端开发者在 `pixflow-web` 运行 `pnpm lint`，会检查 Vue Single-File Component、TypeScript 和 JavaScript 中的模板、语言与 promise 使用问题；后端开发者从仓库根目录运行 `mvn verify`，会先用基于《阿里巴巴 Java 开发手册》的 Checkstyle 约束 Java 源码，再用 SpotBugs 检查编译后字节码中的高置信度缺陷。三种工具都固定版本、共享仓库配置、不依赖 IDE，并对未进入存量基线的新违规返回非零退出码。

现有代码中的违规不会在首次接入时被混入一次全仓格式化或业务重构。ESLint 使用自身的 bulk suppressions 记录现存违规计数，Checkstyle 使用精确到文件、check 和行号的 suppression，SpotBugs 使用精确到 bug pattern 与 class/method 的 exclude filter。实施者必须先审阅误报和规则噪声，再冻结基线；后续触达代码时逐步删除已失效的 suppressions。

本计划引入的是全部三类推荐 linter/静态检查器：ESLint、Checkstyle、SpotBugs。Prettier 和 Spotless 是 formatter，不是 linter，因此不纳入本计划；PMD、Error Prone 与 Sonar 也按调研结论暂缓，避免与前三者重叠并放大初始噪声。格式化工具、pre-commit 和 CI workflow 可在本计划稳定后单独实施。

## Progress

- [x] (2026-07-16) 阅读 `AGENTS.md`、`PLANS.md`、`docs/design-docs/index.md`、全部当前执行计划和 `docs/research/linter-adoption-recommendation-2026-07-16.md`。
- [x] (2026-07-16) 核对根 Maven reactor、`pixflow-web` 的 Vue/TypeScript 配置、当前脚本、仓库文档、CI 文件和工作树状态；确认三种 linter 均未配置，仓库也没有可直接修改的 CI workflow。
- [x] (2026-07-16) 核对阿里巴巴 P3C 官方仓库；确认官方实现是 PMD/IDE 插件，没有可直接交给 Maven Checkstyle Plugin 的官方 Checkstyle XML。
- [x] (2026-07-16) 根据用户补充要求，把原 Checkstyle 单项计划扩展并改名为本完整 linter ExecPlan。
- [x] (2026-07-16) Milestone 1：引入 ESLint 9 flat config、Vue/TypeScript type-aware rules、严格 `pnpm lint` 和 bulk suppressions；647 条存量分布在 87 个文件、174 个 file/rule pair，前端 lint/typecheck/114 tests/build 全部通过。
- [x] (2026-07-16) Milestone 2：引入 Checkstyle 13.8.0 与仓库自有阿里规范映射；审阅 4,940 条存量并归并为 689 个精确到 file/check/lines 的 suppression。
- [x] (2026-07-16) Milestone 3：引入 SpotBugs 4.10.3；High audit 发现 compact record constructor 的 3 个同 pattern 告警，以 2 个 pattern/class/constructor Match 精确过滤。
- [x] (2026-07-16) Milestone 4：Checkstyle 与 SpotBugs 默认严格绑定 Maven `verify`，补齐三份开发文档、README 链接和三种负向探针；探针删除后恢复成功。
- [x] (2026-07-16) 完成 linter 与前端全量验证：`mvn -DskipTests verify` 的 30 模块 reactor 通过，耗时 05:59；`mvn verify` 被本机 Docker pipe 缺失阻断于 `RedisDisconnectFailureInjectionTest:22`，具体证据已记录。

## Surprises & Discoveries

- Observation: 调研报告明确推荐 ESLint、Checkstyle 和 SpotBugs 三种工具，而不是用一把跨语言工具覆盖全仓；Prettier/Spotless 只承担格式化职责。
  Evidence: `docs/research/linter-adoption-recommendation-2026-07-16.md` 的“推荐工具与职责”“明确不建议首期引入”和“建议的落地顺序”。

- Observation: `pixflow-web/package.json` 目前只有 `typecheck`、`test`、`build` 等脚本，没有 ESLint 依赖、flat config 或 lint script；`tsconfig.json` 已启用 `strict` 与 `noUncheckedIndexedAccess`。
  Evidence: `pixflow-web/package.json` 与 `pixflow-web/tsconfig.json`。类型检查已有较强基础，但不会检查 Vue template、未处理 promise 或一般 lint 规则。

- Observation: 阿里巴巴官方 `alibaba/p3c` 当前没有 Checkstyle 配置或 Checkstyle 模块，其可执行规则建立在 PMD 与 IDE 插件上。
  Evidence: 2026-07-16 查询官方仓库 `master` 目录树，搜索 `checkstyle` 无命中，而仓库包含 `p3c-pmd`。因此本文不把任何第三方 XML称为“阿里官方 Checkstyle”。

- Observation: 根 `pom.xml` 是包含 29 个 Java 子模块的 Java 21 Maven reactor，当前 `pluginManagement` 只管理 Spring Boot 插件；全仓没有 Checkstyle、SpotBugs、PMD 或 Error Prone 配置。
  Evidence: 根 `pom.xml` 的 `<modules>`、`<java.version>21</java.version>` 和 `<build><pluginManagement>`，以及全仓配置搜索。

- Observation: 两个当前执行计划都记录过全仓测试被与 linter 无关的既有失败阻断。
  Evidence: `docs/design-docs/exec-plans/execution-domain-refactor-plan.md` 记录 `pixflow-context`/`pixflow-eval` 的既有失败；`docs/design-docs/exec-plans/rubrics-criterion-verdict-refactor-plan.md` 记录并行重构期间的 reactor 阻断。最终必须分别报告 linter 与测试结果，不能互相冒充。

- Observation: 当前 `node_modules` 由 pnpm v11 store 链接，而仓库固定 pnpm 10.15.0；pnpm 10 首次 add 报 `ERR_PNPM_UNEXPECTED_STORE`。
  Evidence: 先用 `pnpm install --lockfile-only` 更新 lockfile，再按 lockfile `pnpm install --force` 重建本地依赖；未改变仓库固定 pnpm 版本，随后四项前端验证均通过。

- Observation: `eslint-plugin-vue` 的 flat recommended preset默认把大量模板规则设为 warning，而 ESLint bulk suppressions 不记录 warning；仅加 `--max-warnings 0` 会让存量 warning 永久阻断。
  Evidence: 初次正确解析 SFC 后报告 476 warnings。配置只把 preset 中 severity=warning 的启用规则提升为 error，再生成 bulk suppression；最终基线为 647 violations，严格 lint 零输出通过且新增违规会失败。

- Observation: Checkstyle 首次全仓 audit 的 4,940 条违规高度集中于机械布局规则，但规则本身能稳定解析 Java 21，连续扫描位置稳定。
  Evidence: `EmptyLineSeparator` 1,927、`WhitespaceAround` 1,003、`LeftCurly` 644、`WhitespaceAfter` 414、`LineLength` 395，其余 557；归并后为 689 个 file/check/lines suppression，严格 reactor 每模块均报告 0 unsuppressed violations。

- Observation: SpotBugs High 首次 audit 只发现 3 个 `SA_LOCAL_SELF_ASSIGNMENT_INSTEAD_OF_FIELD`，来自两个 compact record constructor 的显式自赋值。
  Evidence: `CopyContext` 的 `skuId`/`productName` 两项和 `ImagegenPlan.prompt` 一项；filter 仅匹配该 pattern、具体 class 与 `<init>`，删除条件写入注释。严格 reactor 每模块均为 0 unfiltered bugs。

- Observation: 包含测试的最终 `mvn verify` 在当前机器无法启动 Testcontainers，而不是被 linter 或代码断言阻断。
  Evidence: `Test-Path \\.\pipe\docker_engine` 返回 `False`；`RedisDisconnectFailureInjectionTest.disconnectedRedisFailsClosedWithoutRunningProtectedAction` 在第 22 行抛 `IllegalStateException: Could not find a valid Docker environment`，reactor 后续模块未执行。

## Decision Log

- Decision: 本计划的“所有 linter”指调研报告推荐的 ESLint、Checkstyle 和 SpotBugs；不包含 formatter，也不额外引入 PMD、Error Prone 或 Sonar。
  Rationale: 这三者分别覆盖 Vue/TypeScript 源码、Java 源码规范和 Java 字节码缺陷，职责互补。继续叠加静态检查器会扩大重复告警，违背首期窄范围接入原则。
  Date/Author: 2026-07-16 / Codex

- Decision: 前端固定 ESLint 9.39.2、`@eslint/js` 9.39.2、`typescript-eslint` 8.64.0、`eslint-plugin-vue` 10.9.2 和 `globals` 17.7.0，使用 `eslint.config.js` flat config。
  Rationale: 调研报告指定 ESLint 9；这些版本支持 flat config、Vue 3、TypeScript 5.9 和当前 Node 18+ 基线。即使 ESLint 10 已发布，也不在首次接入时同时做 major upgrade。
  Date/Author: 2026-07-16 / Codex

- Decision: 后端固定 `maven-checkstyle-plugin` 3.6.0、Checkstyle 13.8.0、`spotbugs-maven-plugin` 4.10.3.0 和 SpotBugs 4.10.3。
  Rationale: 截至计划编写日，它们是 Maven Central 的对应最新 release。显式固定插件与引擎版本可避免默认传递版本变化；实施时必须先用最小配置验证 Java 21 兼容性。
  Date/Author: 2026-07-16 / Codex

- Decision: “阿里巴巴 Checkstyle”在本仓库中定义为 Maven Checkstyle Plugin 执行一份 PixFlow 自有、按《阿里巴巴 Java 开发手册》逐项映射的 XML profile；不引入 P3C PMD，也不采用来源不明的第三方 XML。
  Rationale: 官方 P3C 没有 Checkstyle 配置。这个定义保留用户要求的 Checkstyle 工具和阿里规范语义，同时保证规则来源、修改与兼容性可审查。
  Date/Author: 2026-07-16 / Codex

- Decision: 首期 ESLint 覆盖 `pixflow-web/src/**/*.{ts,tsx,vue}`，Checkstyle 与 SpotBugs 只覆盖各模块 production Java/class；测试源码、生成物、`target`、`dist` 和 coverage 暂不纳入。
  Rationale: 这是调研报告阶段 1 的范围。测试 lint、构建脚本 lint 和生成代码策略需要首次告警数据，不能在没有证据时与 production 使用同一门禁。
  Date/Author: 2026-07-16 / Codex

- Decision: 三种工具各自采用可审阅的存量基线，最终默认命令严格失败。非阻断 audit 只用于首次扫描和规则升级，不得成为日常默认。
  Rationale: 首次接入不能要求清理所有历史问题，但只生成报告、不影响退出码也不构成门禁。精确基线使旧债可见并阻止告警数或新位置增长。
  Date/Author: 2026-07-16 / Codex

- Decision: Checkstyle 存量 suppression 至少限定文件、check 和行号；SpotBugs filter 至少限定 bug pattern 与具体 class，能限定 method/field 时继续收窄；禁止全模块、全 package 或全 bug category 排除。
  Rationale: 宽泛排除会让同一位置后续新增问题静默通过。精确条目虽然需要维护，但能把债务变成可删除、可审阅的事实。
  Date/Author: 2026-07-16 / Codex

- Decision: 把 Vue recommended preset 中启用的 warning 统一提升为 error，并让默认 `lint` 同时使用 `--max-warnings 0`。
  Rationale: ESLint bulk suppressions只冻结 error 计数；若保留 preset warning，则存量无法进入基线且新增 warning 也不能同时满足默认严格门禁。转换只改变 severity，不启用 preset 原本关闭的规则。
  Date/Author: 2026-07-16 / Codex

- Decision: `linters-audit` profile 让 Checkstyle 保持同一规则但不因违规失败，并跳过默认 SpotBugs check、改跑同配置的 report goal；默认 lifecycle 始终执行严格 check goals。
  Rationale: 两种插件的非阻断参数不同。显式 profile execution 能保证 audit 和 gate 使用相同 threshold/filter，同时避免把日常默认变成只生成报告。
  Date/Author: 2026-07-16 / Codex

## Outcomes & Retrospective

计划已完成。前端固定 ESLint 9.39.2、`@eslint/js` 9.39.2、typescript-eslint 8.64.0、eslint-plugin-vue 10.9.2 和 globals 17.7.0；bulk baseline 含 87 个文件、174 个 file/rule pair、647 个存量 violation。严格 lint 在并行验证时耗时 33.3 秒；typecheck 7.3 秒、114 tests 18.3 秒、build 24 秒均成功。临时 TS probe 同时触发 `@typescript-eslint/no-unused-vars` 与 `no-floating-promises`，退出码 1，删除后恢复。

后端固定 maven-checkstyle-plugin 3.6.0 + Checkstyle 13.8.0，以及 spotbugs-maven-plugin 4.10.3.0 + SpotBugs 4.10.3。Checkstyle 首次发现 4,940 条生产源码存量，按 689 个 file/check/lines 条目冻结；临时 wildcard import probe 稳定触发 `AvoidStarImport`。SpotBugs High 首次发现 3 个 compact-record self-assignment 告警，用 2 个 pattern/class/constructor filter 条目记录；临时 null dereference probe 稳定触发 `NP_ALWAYS_NULL`。三个 probe 均未留在工作树。

严格 `mvn -DskipTests verify` 对 30 模块全部成功，耗时 05:59。完整 `mvn verify` 已实际运行，但当前机器没有 Docker engine pipe，在 infra-cache 的 Testcontainers failure-injection test 处失败；这不改变 linter gate 已完整通过的结论。实现与原计划唯一实质细化是把 Vue preset warning 提升为 error，使 ESLint bulk baseline 与 `--max-warnings 0` 能同时成立。

## Context and Orientation

仓库根目录是 `D:\study\PixFlow`。后端根 `pom.xml` 的 packaging 是 `pom`，列出 29 个 Java 子模块并把插件配置继承给它们。`pluginManagement` 只固定默认值；插件还必须出现在根 `<build><plugins>`，子模块生命周期才会实际执行。Checkstyle 分析源码，可在编译前运行；SpotBugs 分析 `.class`，必须在 compile/package 之后运行。因此两者都绑定 `verify`，但不能用相同的报告或 suppression 格式。

前端位于 `pixflow-web`，使用 Vue 3、TypeScript 5.9、Vite、Vitest 和 pnpm 10。ESLint flat config 是 `pixflow-web/eslint.config.js`；它组合 `@eslint/js`、`typescript-eslint` 和 `eslint-plugin-vue` 的 flat presets，并用 `globals` 声明浏览器、Node 与 Vitest 环境。`pnpm typecheck` 继续独立执行，因为 ESLint 不替代 TypeScript 类型检查。

ESLint bulk suppressions 是 `pixflow-web/eslint-suppressions.json`。ESLint 记录每个文件、每条规则当前被抑制的违规数量；同一文件同一规则新增超过基线的违规仍会报告。删除代码使 suppression 过量时，严格 lint 应失败并提示运行 prune，使基线只减不增。不要把 bulk suppression 改成散落在 Vue/TS 源码里的 `eslint-disable` 注释。

Checkstyle 规则文件是 `config/checkstyle/alibaba-checkstyle.xml`，存量基线是 `config/checkstyle/suppressions.xml`。规则覆盖阿里规范中可由 Checkstyle 标准 check 稳定表达的命名、import、空白、120 字符行宽、大括号、隐式 fall-through、空语句、重复 modifier 和基础 equals/hashCode 约束。每组规则应有阿里规范主题注释。首期不强制全量 Javadoc、全局 final、复杂度、文件长度或包架构；无法准确表达的语义规则不能用脆弱正则冒充。

SpotBugs 配置文件是 `config/spotbugs/exclude-filter.xml`，报告写入各模块 `target/spotbugsXml.xml`。首期使用 `effort=Default`、`threshold=High`，只门禁高优先级缺陷；包括高置信度空值、资源关闭、同步/并发和错误 API 使用。filter 条目必须带注释，说明为何是已确认存量或误报以及未来删除条件。SpotBugs 不是格式检查器，不与 Checkstyle 规则重复。

## Plan of Work

### Milestone 1：引入 ESLint 并冻结前端基线

在 `pixflow-web/package.json` 精确加入 ESLint 9.39.2、`@eslint/js` 9.39.2、`typescript-eslint` 8.64.0、`eslint-plugin-vue` 10.9.2 和 `globals` 17.7.0，并更新 `pixflow-web/pnpm-lock.yaml`。新增脚本 `lint`、`lint:fix`、`lint:audit` 和 `lint:prune-suppressions`。`lint` 必须是只读严格检查；`lint:fix` 只供开发者显式调用，实施本计划时不得用它批量改仓库。

新增 `pixflow-web/eslint.config.js`。配置覆盖 `src/**/*.{ts,tsx,vue}`，忽略 `dist`、`coverage`、生成物和依赖目录；启用 JavaScript recommended、Vue 3 recommended、TypeScript recommended，并对需要类型信息的 TS/Vue 文件启用 `typescript-eslint` type-checked 配置。明确启用 `@typescript-eslint/no-unused-vars`、`@typescript-eslint/no-floating-promises`、`eqeqeq`、`prefer-const` 与 Vue 模板未使用变量/无效指令等高置信规则；关闭与 TypeScript 编译器重复且由编译器更准确处理的规则。`vue/multi-word-component-names` 若与现有页面组件命名冲突，应依据实际文件审阅后记录决定，不能静默关闭。

先运行无 suppression 的 audit，区分配置错误、parser/project 错误和真实违规。确认规则集后使用 ESLint 9 bulk suppression 命令生成 `pixflow-web/eslint-suppressions.json`，随后运行 prune 并检查文件内容。禁止新增源码内 `eslint-disable`、整目录 ignore 或把 recommended rules 全部降为 warning。严格 `pnpm lint` 必须在基线上成功，新增超过基线计数的违规必须失败。

本里程碑验收包括 `pnpm lint`、`pnpm typecheck`、`pnpm test`、`pnpm build` 各自成功；临时向一个现有或探针 TS 文件加入未处理 promise 与未使用变量后，`pnpm lint` 返回非零，移除后恢复成功。

### Milestone 2：引入基于阿里规范的 Checkstyle

在根 `pom.xml` properties 固定 Checkstyle plugin 与 engine 版本。在 `<build><pluginManagement>` 配置插件和显式 engine dependency，并在 `<build><plugins>` 声明绑定 `verify` 的 `check` execution。配置 UTF-8、`includeTestSourceDirectory=false`、console output、`${maven.multiModuleProjectDirectory}` 下的共享配置，以及各模块独立的 cache/report 路径。

新增 `config/checkstyle/alibaba-checkstyle.xml`、`config/checkstyle/suppressions.xml` 和 `config/checkstyle/README.md`。从最小 `Checker + TreeWalker + SuppressionFilter` 开始，先验证 Java 21 解析与跨模块路径，再按 Context 中的规则边界逐组启用。使用默认不激活的 `linters-audit` Maven profile或显式非阻断属性完成首次扫描；配置加载错误和 Java 21 parse error 必须先修复，不能进 suppression。

汇总所有模块的 `target/checkstyle-result.xml`，审阅规则噪声，把确认的存量违规写入 suppression。每项至少限定 `files + checks + lines`，路径正则兼容 Windows/Unix；禁止整模块、整文件或全 check 排除。最终严格 execution 设置 `failOnViolation=true`，`mvn -DskipTests verify` 不得靠 audit profile 才能运行 Checkstyle。

本里程碑验收是连续两次 audit 得到相同结果，严格 `mvn -pl pixflow-common -DskipTests verify` 成功；删除一个仍对应真实违规的 suppression 会稳定恢复该告警。

### Milestone 3：引入 SpotBugs 并冻结字节码基线

在根 POM 为 `spotbugs-maven-plugin` 固定 4.10.3.0，并显式固定 SpotBugs engine 4.10.3。统一配置 `effort=Default`、`threshold=High`、XML output、`failOnError=true`、各模块独立报告路径和根目录 exclude filter；只分析 production classes。插件的 `check` goal 绑定 `verify`，保证在 class 产生后运行。

新增 `config/spotbugs/exclude-filter.xml` 和 `config/spotbugs/README.md`。先用 audit profile 运行全 reactor，区分缺失依赖/分析器崩溃与真实 bug；前两者属于配置失败，不能进入 filter。逐条审阅 High 告警：真实且容易安全修复的问题另记后续任务，本计划不改业务代码；确认的存量或误报用 `Bug pattern + Class`，必要时加 `Method`/`Field` 精确过滤，并写明理由。禁止 package-wide、category-wide 和 `.*` 全局 filter。

严格模式由 `spotbugs:check` goal 自身在发现未过滤 bug 时失败；`failOnError=true` 负责让分析器错误、报告读取错误等工具故障也失败。不要照搬 Checkstyle 的 `failOnViolation` 参数。audit profile 运行生成 XML 的 `spotbugs` goal而不运行严格 `check`，规则、threshold 与 filter 保持相同。本里程碑验收是所有含 production class 的模块产生 SpotBugs XML，`mvn -DskipTests verify` 严格成功；临时增加一个可稳定触发所启用 High pattern 的最小 Java probe 后，SpotBugs `check` 失败并显示 bug type，删除 probe 后恢复成功。

### Milestone 4：统一入口、文档和完整验证

新增 `docs/development/linting.md`，分别说明 `pnpm lint`、`pnpm typecheck`、Checkstyle/SpotBugs audit、单模块与全 reactor 严格命令，三种基线位置，如何审阅误报，以及 suppression/filter 只能减少或经证据审查增加的规则。更新根 `README.md` 文档区链接，并把快速开始的 JDK 17+ 修正为与根 POM一致的 JDK 21。

保留前后端独立入口，不创建一个脆弱的跨生态 shell wrapper。未来 CI 的标准步骤是前端 `pnpm --dir pixflow-web lint`、`typecheck`、`test`，后端 `mvn verify`。仓库当前没有 CI workflow，本计划不猜测平台；这些命令写入开发文档，CI 接入作为独立小任务。

执行前端和两类后端负向探针，保存简短失败证据后逐一删除。最后运行全部严格检查和现有测试/构建，更新 `Progress`、`Surprises & Discoveries`、`Outcomes & Retrospective` 与 `Revision Notes`。

## Concrete Steps

所有命令从 `D:\study\PixFlow` 执行。开始前保存用户工作树状态：

    git status --short
    git diff -- pom.xml README.md pixflow-web/package.json pixflow-web/pnpm-lock.yaml

完成 ESLint 配置后运行：

    pnpm --dir pixflow-web lint:audit
    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build

`lint:audit` 用于首次报告/生成 bulk suppressions；`lint` 必须严格且只读。实施者应把实际 ESLint 9 bulk suppression CLI 参数写入 `pixflow-web/package.json` scripts，避免文档与命令漂移。

完成 Checkstyle 与 SpotBugs 最小接线后运行：

    mvn -Plinters-audit -DskipTests verify
    rg --files -uu -g "**/target/checkstyle-result.xml" -g "**/target/spotbugsXml.xml"
    rg -n -uu "<error |<BugInstance " -g "**/target/checkstyle-result.xml" -g "**/target/spotbugsXml.xml"

完成基线后连续运行两次 audit，结果数量应相同。启用严格 execution 后运行：

    mvn -pl pixflow-common -DskipTests verify
    mvn -DskipTests verify

按三个里程碑分别添加前端 ESLint、Java Checkstyle 和 Java SpotBugs 临时 probe，确认失败后删除单个 probe 并重跑对应严格命令。探针不得提交。

最终运行：

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build
    mvn verify
    git diff --check
    git status --short

`mvn verify` 会运行仓库测试。如果被当前执行计划已经记录的无关测试失败阻断，必须保存完整失败测试名和第一个相关 stack frame，再用 `mvn -DskipTests verify` 证明全 reactor 的 Checkstyle 与 SpotBugs 严格通过，并运行触达模块的定向测试。不得把跳过测试写成所有测试通过，也不得因为既有测试失败而关闭 linter。

## Validation and Acceptance

实现只有同时满足以下可观察行为才算完成。

在干净 checkout、JDK 21、Maven 3.6+、Node 18+ 和 pnpm 10 环境中，不安装 IDE 插件即可运行全部三种 linter。版本固定在 lockfile 或根 POM；规则和 baseline/filter 均在仓库内，构建不下载活动分支上的配置。

`pnpm --dir pixflow-web lint` 覆盖 `src` 下 TS/TSX/Vue 并严格成功。它使用 ESLint 9 flat config 和 bulk suppressions；新增未使用变量、未处理 promise 或 Vue 模板违规会返回非零。`typecheck` 继续独立成功，证明 ESLint 没有替代 TypeScript。

`mvn -DskipTests verify` 在全部 Java 模块执行 `maven-checkstyle-plugin:3.6.0:check` 和 `spotbugs-maven-plugin:4.10.3.0:check` 并成功。日志或 dependency/debug evidence 能确认 Checkstyle 13.8.0 与 SpotBugs 4.10.3；测试源码与生成源码不在首期结果中，production 源码/class 不能被模块级宽泛排除。

前端 ESLint probe、后端 Checkstyle probe 和 SpotBugs probe 都能使对应默认严格命令以非零退出，并输出准确文件与 rule/check/bug type；删除 probe 后恢复成功。这证明三种工具都是门禁，而不只是生成报告。

`config/checkstyle/README.md` 说明阿里规范主题到 checks 的映射及延期项；`config/spotbugs/README.md` 说明 threshold、filter 审阅与误报处理；`docs/development/linting.md` 提供所有本地命令。ESLint bulk suppressions、Checkstyle suppressions 和 SpotBugs filter 都不包含整仓、整模块或整 package 的宽泛绕过。

最终 diff 只包含 linter 依赖/配置/基线、开发文档、README 链接和本计划更新。不得包含批量格式化、production/test 业务重构、Prettier/Spotless、PMD/Error Prone/Sonar 或 pre-commit/CI 平台配置。

## Idempotence and Recovery

配置与文档修改使用小范围补丁。ESLint cache（若启用）、Maven reports 和 caches 只写入可再生目录；规则与三类基线均受版本控制。重复运行 audit 与严格命令不得修改源码或 lockfile。

当前工作树包含大量用户的执行域、Rubrics、前端和设计文档修改。不得执行 `git reset --hard`、`git checkout --`、整体 restore 或全仓 formatter。若 `pom.xml`、`pixflow-web/package.json`、lockfile、README 或本计划出现并行修改，先重新读取并做小范围合并，不能覆盖用户变更。

如果版本组合无法解析 Java 21、Vue 或 TypeScript 5.9，先建立最小复现并记录错误。选择最近的兼容 release 组合后同时固定 plugin/engine 或 ESLint ecosystem 版本，并更新 Decision Log；不得删除显式版本让结果依赖传递默认值。

若某个规则大量误报，先修正规则范围、parser options 或分析 classpath；只有规则准确而违规确为存量时才能进入基线。不得自动重写基线使构建变绿。升级工具或规则时先走 audit，在独立变更中审阅新增告警并 prune 已失效条目。

三个负向探针必须逐一删除。若验证中断，先用 `git status --short` 识别本文命名的 probe，再删除该单一文件；不要用目录级清理命令。恢复后重跑对应严格命令并确认没有 probe 留在最终 diff。

## Artifacts and Notes

目标文件布局：

    pom.xml
    config/checkstyle/alibaba-checkstyle.xml
    config/checkstyle/suppressions.xml
    config/checkstyle/README.md
    config/spotbugs/exclude-filter.xml
    config/spotbugs/README.md
    pixflow-web/package.json
    pixflow-web/pnpm-lock.yaml
    pixflow-web/eslint.config.js
    pixflow-web/eslint-suppressions.json
    docs/development/linting.md
    docs/design-docs/exec-plans/linter-adoption-plan.md

目标验证流：

    pnpm --dir pixflow-web lint
        -> ESLint 9 flat config
        -> Vue + TypeScript rules
        -> eslint-suppressions.json
        -> zero new violations, or non-zero exit

    mvn verify
        -> Checkstyle on production Java source
        -> config/checkstyle/alibaba-checkstyle.xml + suppressions.xml
        -> compile/test/package
        -> SpotBugs on production class files
        -> config/spotbugs/exclude-filter.xml
        -> zero new violations, or BUILD FAILURE

实施完成时在此分别追加三种工具的首次告警总数、主要规则/bug pattern、最终基线条目和负向探针失败摘要。每种工具保留几行最小证据，不粘贴完整日志。

## Interfaces and Dependencies

本计划不新增 Java runtime interface、业务依赖或 Spring bean。新增依赖全部属于构建/开发工具：

    pixflow-web devDependencies:
        eslint 9.39.2
        @eslint/js 9.39.2
        typescript-eslint 8.64.0
        eslint-plugin-vue 10.9.2
        globals 17.7.0

    Maven build plugins:
        org.apache.maven.plugins:maven-checkstyle-plugin 3.6.0
            -> com.puppycrawl.tools:checkstyle 13.8.0
        com.github.spotbugs:spotbugs-maven-plugin 4.10.3.0
            -> com.github.spotbugs:spotbugs 4.10.3

根 POM 最终必须有两个默认启用、绑定 `verify` 的 execution：`checkstyle-verify` 调用 `check`，`spotbugs-verify` 调用 `check`。Checkstyle 共享配置路径使用 `${maven.multiModuleProjectDirectory}/config/checkstyle/...`，SpotBugs exclude filter 使用 `${maven.multiModuleProjectDirectory}/config/spotbugs/exclude-filter.xml`；各模块报告/cache 写入自己的 `${project.build.directory}`，避免并行 reactor 构建互相覆盖。

`linters-audit` profile 只能改变失败行为或生成详细报告，不能切换成另一套规则。默认构建必须使用与 audit 相同的 ESLint config、Checkstyle XML 和 SpotBugs threshold/filter。这样 audit 与门禁看到的是同一个问题集合，差别只在是否立即阻断。

## Revision Notes

2026-07-16 / Codex: 创建原 Checkstyle 单项计划。依据调研报告和“使用阿里巴巴的 Checkstyle”的要求，采用 Maven Checkstyle Plugin + 仓库内阿里规范映射，不把官方 P3C 的 PMD 实现或第三方 XML冒充为官方 Checkstyle。

2026-07-16 / Codex: 根据用户“不仅要引入 Checkstyle，还要把其他所有 linter 都引入”的补充要求，将计划改名并扩展为完整 linter 计划。新增 ESLint 9 flat config 与 bulk suppression、SpotBugs 高优先级字节码检查与精确 filter、三种负向探针和统一开发文档；仍明确排除 formatter、重复的 PMD/Error Prone/Sonar、业务代码批量修复和未知 CI 平台配置。

2026-07-16 / Codex: 完成四个实施里程碑。前端加入 type-aware ESLint/Vue flat config 和 647 条 bulk baseline；后端加入 Checkstyle/SpotBugs 默认严格 verify execution、4,940 条源码存量的 689 项精确 suppression、3 个 High finding 的 2 项精确 filter。补齐开发文档、README JDK 21 修正、三类负向探针和恢复验证。30 模块 `mvn -DskipTests verify` 与全部前端命令通过；完整测试仅被当前机器缺失 Docker pipe 阻断，已记录测试名与栈帧。

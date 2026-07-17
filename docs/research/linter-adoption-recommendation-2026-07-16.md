# PixFlow linter 引入建议

调研日期：2026-07-16  
范围：根 Maven reactor（Java 21）与 `pixflow-web`（Vue 3 + TypeScript）。  
结论：不要选择一把覆盖所有语言的工具；应按生态引入 **ESLint**（前端）和 **Checkstyle + SpotBugs**（后端），并用格式化工具作为配套，而非把格式化器误当成 linter。

## 仓库事实

- 根 `pom.xml` 是 30 个模块的 Maven reactor，设置 `java.version=21`，当前只管理 Spring Boot 插件；未配置 Checkstyle、SpotBugs、PMD、Error Prone 或格式化插件。
- `pixflow-web/package.json` 采用 Vue 3、TypeScript、Vite、Vitest 与 pnpm 10；脚本只有 `typecheck`、`test`、`build`，没有 lint 脚本或 ESLint 配置。
- 前端已启用 `strict` 与 `noUncheckedIndexedAccess`；这些是重要的类型检查，但不覆盖 Vue 模板、未使用变量、导入顺序、可疑 promise 等 lint 类问题。
- 当前工作树含大量正在进行的领域重构，因此首次接入不能要求一次性清理全仓历史告警，也不应在本次执行域/上传/评估计划中顺带改业务代码。

## 推荐工具与职责

| 代码 | 建议 | 首期职责 | 不承担的职责 |
| --- | --- | --- | --- |
| Vue/TypeScript | ESLint 9 flat config、`eslint-plugin-vue`、`typescript-eslint` | Vue SFC、TS/JS 与模板静态规则 | 类型正确性（继续由 `tsc --noEmit` 负责） |
| Java | Checkstyle | 可读性、一致性与明确的源码约束 | bug 模式分析、自动格式化 |
| Java | SpotBugs | 字节码级缺陷模式（空值、资源、并发、错误 API 使用等） | 风格规范 |
| 两端配套 | Prettier（web）与 Spotless（Java） | 可重复的格式检查/可选自动修复 | 语义 lint |

### 为什么是这组组合

1. ESLint 是 Vue 与 TypeScript 官方生态采用的 lint 编排器；`eslint-plugin-vue` 提供 Vue SFC 规则，`typescript-eslint` 提供 TypeScript parser、plugin 和 flat-config presets。它与现有 Vite/Vitest/TypeScript 结构直接匹配。
2. Checkstyle 对 Maven 多模块的源码约束最直接、规则可审查。首期应使用项目自己的小型规则集，避免直接套 Google/Sun 全量规则而产生大量与现有代码无关的阻断。
3. SpotBugs 与 Checkstyle 互补：前者分析已编译 class 的常见缺陷，后者不做的并发、空值和资源问题可以在 CI 中被发现。对于包含异步任务、I/O、锁与并发执行的 PixFlow，这一层值得保留。
4. Prettier/Spotless 应只解决机械排版；将其结果和 lint 告警分开，开发者能清楚知道一次失败是“自动修复即可”还是“需要判断”。

## 明确不建议首期引入

- **不要只引入 Prettier 或 Spotless**：它们是 formatter，不是 linter，不能替代上表前三项。
- **不要首期同时引入 PMD、Error Prone、Sonar 规则集**：三者与 SpotBugs/Checkstyle 的规则重叠会显著放大基线告警。先积累一轮真实问题和 suppressions，再评估 Error Prone（编译期）或 SonarQube（平台级质量门禁）的必要性。
- **不要把所有警告当 error**：先只阻断高置信度 bug/安全规则与新增代码，避免当前活跃重构被历史样式债务卡住。

## 建议的落地顺序

### 阶段 1：可重复、零业务改动的基线

1. 在 `pixflow-web` 加入 `eslint.config.js`（flat config）以及 `lint` / `lint:fix` scripts；覆盖 `src/**/*.{ts,vue}`，排除 `dist`、coverage 与生成物。
2. 启用 Vue recommended + TypeScript recommended；首期把模板中的未使用变量、未处理 promise、重复导入等设为 error。不要重复启用已由 TypeScript 严格检查更准确处理的规则。
3. 在根 POM 的 `pluginManagement` 固定 `maven-checkstyle-plugin` 与 `spotbugs-maven-plugin`，随后在 `<build><plugins>` 统一执行。规则文件放在根目录 `config/checkstyle/checkstyle.xml`，不散落到 30 个模块。
4. Checkstyle 首期只检查 production Java：空白、import、重复 modifier、非法 token、避免通配 import 和基本 Javadoc/命名约束。明确排除 `target/`、生成源码、迁移 SQL 和测试代码；测试规则另行决定。
5. SpotBugs 首期以 `Default` 或 `Medium` effort 运行，先启用高优先级缺陷；把经审阅的误报集中放入一个带注释的 filter XML，不在代码中大面积加 suppression。
6. 只在本地与 CI 中运行 `pnpm lint`、`pnpm typecheck`、`mvn verify`；先生成基线清单，再决定哪些旧告警要修。没有 CI 文件时，先把命令写入 README/开发文档，CI 接入作为后续独立小任务。

### 阶段 2：质量门禁

1. 采用“修改文件零新增违规”策略：旧代码有已确认基线，变更不得增加 Checkstyle/SpotBugs/ESLint 告警。
2. CI 对前端执行 `pnpm --dir pixflow-web lint && pnpm --dir pixflow-web typecheck && pnpm --dir pixflow-web test`；后端执行 `mvn verify`。
3. 稳定两周后，再将确认无争议的存量告警逐类收紧；不要以一次全仓格式化提交掩盖真实改动。

### 阶段 3：开发体验

- 用 pre-commit 仅运行 staged 前端 lint/format 与受影响 Java 模块的快速检查；完整 SpotBugs/Maven reactor 留给 CI，避免提交延迟。
- 编辑器统一使用 ESLint 与 Checkstyle/SpotBugs 的诊断；自动格式化仅在保存时运行对应 formatter。

## 初始规则边界

前端首期优先：`vue/multi-word-component-names`（可按现有命名决定是否关闭）、Vue 模板未使用变量、`@typescript-eslint/no-unused-vars`、`@typescript-eslint/no-floating-promises`、`eqeqeq`、`prefer-const`、import 重复/循环（如规则验证无误）。不要强制复杂度、文件长度或主观命名规则。

后端首期优先：禁止 `*` import、未使用 import、重复 modifier、空白/缩进/换行、隐式 fall-through、危险 equals/hashCode 约束；SpotBugs 的高优先级空值、资源关闭、错误同步与并发缺陷。不要先强制 100 列行宽、全覆盖 Javadoc、全局 final 或包结构规则；这些会与现有风格和领域边界检查混为一谈。

## 验收标准

- 新 clone 后，前端 `pnpm lint` 和 `pnpm typecheck` 都可独立执行；后端 `mvn verify` 包含 Checkstyle 与 SpotBugs 检查。
- 所有工具版本在 lockfile 或根 POM 中固定；配置不依赖某位开发者的 IDE。
- 有一份短文档说明：本地命令、排除范围、如何处理误报、何时可以新增 suppression。
- 首次 PR 不混入格式化全仓重写；已有未提交领域改动保持原样。

## 一项可执行的后续任务

建议将“接入阶段 1 基线”作为一个独立、窄范围执行计划：新增配置和脚本、运行一次全仓扫描、记录基线，**不修复**扫描发现的业务问题。完成后再单开任务处理最有价值的一批告警。

## 一手资料

- [ESLint：Configure ESLint（flat config）](https://eslint.org/docs/latest/use/configure/configuration-files)
- [eslint-plugin-vue：User Guide](https://eslint.vuejs.org/user-guide/)
- [typescript-eslint：Getting Started](https://typescript-eslint.io/getting-started/)
- [Apache Maven Checkstyle Plugin：Usage](https://maven.apache.org/plugins/maven-checkstyle-plugin/usage.html)
- [SpotBugs Maven Plugin：Usage](https://spotbugs.github.io/spotbugs-maven-plugin/usage.html)
- [Prettier：CLI](https://prettier.io/docs/cli)
- [Spotless Maven Plugin（项目维护者文档）](https://github.com/diffplug/spotless/tree/main/plugin-maven)

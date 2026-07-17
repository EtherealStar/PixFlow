# Linting and static analysis

PixFlow uses separate tools for the frontend and backend. The tools, rule files, and existing-code baselines are all versioned in the repository and do not depend on IDE plugins.

## Frontend

From the repository root, run:

    pnpm --dir pixflow-web lint
    pnpm --dir pixflow-web typecheck
    pnpm --dir pixflow-web test
    pnpm --dir pixflow-web build

ESLint checks `pixflow-web/src/**/*.{ts,tsx,vue}` with type information and Vue template parsing. `typecheck` remains separate because ESLint does not replace TypeScript. `lint` is read-only and fails on warnings or violations beyond the checked-in baseline.

Existing findings live in `pixflow-web/eslint-suppressions.json`. To review or upgrade rules, run `pnpm --dir pixflow-web lint:audit`; it records the current bulk-suppression counts without editing source. Then run `pnpm --dir pixflow-web lint:prune-suppressions` and inspect the diff. `lint:fix` is an explicit developer action and must not be used for unrelated repository-wide formatting.

Do not add source-level `eslint-disable` comments or broad ignores. A baseline increase requires reviewing the exact file and rule; touched code should prune suppressions that are no longer needed.

## Backend

The default Maven lifecycle is strict:

    mvn -pl pixflow-common -DskipTests verify
    mvn -DskipTests verify
    mvn verify

Checkstyle analyzes production Java source using `config/checkstyle/alibaba-checkstyle.xml`; SpotBugs analyzes production class files at High priority. Their baselines are `config/checkstyle/suppressions.xml` and `config/spotbugs/exclude-filter.xml`.

Before changing rules or tool versions, generate a non-blocking full-reactor audit:

    mvn -Plinters-audit -DskipTests verify

Inspect `**/target/checkstyle-result.xml` and `**/target/spotbugsXml.xml`. Configuration/parser failures and analyzer crashes are tool failures, not suppressible findings. Checkstyle suppressions must identify file, check, and line; SpotBugs filters must identify bug pattern, concrete class, and method or field when available.

## Expected CI commands

The repository currently has no CI workflow. A future workflow should run the frontend `lint`, `typecheck`, and `test` commands separately, and run backend `mvn verify`. Keeping the ecosystems separate makes failures attributable and avoids a platform-specific wrapper.

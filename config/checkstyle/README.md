# Checkstyle profile

`alibaba-checkstyle.xml` is PixFlow's repository-owned Checkstyle profile. It maps rules that standard Checkstyle checks can express reliably to themes from the Alibaba Java Development Manual; it is not an official Alibaba P3C file. Maven pins both the plugin and engine versions in the root `pom.xml`.

The first profile covers:

- naming: types, methods, members, parameters, locals, and constants;
- imports: no wildcard, duplicate, or unused imports;
- layout: 120-character lines, braces, whitespace, empty-line separation, and one statement per line;
- declarations: modifier order, redundant modifiers, and one variable declaration per statement;
- correctness-adjacent source rules: explicit switch fall-through and paired `equals`/`hashCode` implementations.

The first adoption deliberately postpones broad Javadoc coverage, mandatory `final`, complexity/file-size thresholds, package architecture, and semantic rules that Checkstyle cannot model without fragile regular expressions.

`suppressions.xml` is the reviewed production-source baseline from 2026-07-16. Every entry is limited to one file, one check, and explicit line numbers. Do not add module-, package-, file-, or check-wide exclusions. When touched code removes a violation, delete its stale suppression; use the audit profile to review rule upgrades before changing the baseline.

Run a non-blocking report with `mvn -Plinters-audit -DskipTests verify`. The normal `mvn verify` and `mvn -DskipTests verify` commands are strict.

# SpotBugs profile

PixFlow runs SpotBugs 4.10.3 through `spotbugs-maven-plugin` 4.10.3.0 with `effort=Default` and `threshold=High`. It analyzes production bytecode only and writes `target/spotbugsXml.xml` in each Java module.

`exclude-filter.xml` contains only reviewed existing findings or false positives. Each match must name a bug pattern and concrete class; add a method or field whenever the finding permits it. Package-wide, category-wide, and wildcard exclusions are forbidden. Every entry needs a reason and a condition for removing it.

Use `mvn -Plinters-audit -DskipTests verify` to generate reports without failing on findings. Use normal `mvn verify` or `mvn -DskipTests verify` for the strict gate. Analyzer crashes, missing reports, and unfiltered High findings fail the strict build.

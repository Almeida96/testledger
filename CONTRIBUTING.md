# Contributing

Use JDK 21 and the checked-in Gradle wrapper:

```sh
./gradlew test installDist
pnpm install --frozen-lockfile
pnpm test:mcp
```

On Windows, use `gradlew.bat`. The Node.js MCP tests require Node 24 and the distribution built by `installDist`.

Parser changes need real XML fixtures, including a rejected malformed input. Keep DTDs, external entities and paths outside the selected report root disabled. Import failures must leave the ledger unchanged.

History queries must state their run selection and return citations. A status change across runs is evidence for triage; it is not proof that a test is flaky. Keep that distinction in tool descriptions and reports.

Tests and demos run offline with synthetic report data. Before adding fixtures, remove tokens, environment values and personal paths from failure messages. Redaction has limits; it does not make arbitrary CI logs safe to publish.

Run the independent MCP client tests after changing tool schemas or response shapes. Document a database migration before changing persisted tables.

Timing changes must preserve the distinction between a measured increase and insufficient data. Cover both thresholds, ignored observations, branch and time boundaries, and backfills between result pages. Keep gate exit codes independent of the requested page. Duration comparisons must also handle very large finite inputs without emitting non-finite JSON numbers.

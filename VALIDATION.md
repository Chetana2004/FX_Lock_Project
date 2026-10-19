# Checks performed on 16 October 2026

Successfully executed on this source package:

- Clean Maven reactor build: `mvn clean verify`, using OpenJDK 25.0.2 and Maven 3.9.11.
- All seven reactor projects succeeded; all five executable Boot JARs packaged.
- 16 tests passed: AccessControlTest 4, RateSafetyTest 7, PricingTest 5.
- Six OracleSettlementTest scenarios were discovered and explicitly skipped because no Oracle instance was provisioned and RUN_ORACLE_TESTS was not enabled.
- Packaged Eureka application started; GET /eureka/apps returned HTTP 200; process stopped after the check.
- Maven XML parsing, YAML parsing, Python compilation and browser JavaScript syntax checks passed.
- Source archive integrity checked during packaging.

Human-readable test summaries are in validation/. Generated targets, JARs, toolchains, dependency caches and temporary files are excluded from the source archive.

Not executed here: Oracle migration, Oracle-backed application startup, actual settlement concurrency/locking/rollback/reconciliation, Kafka setup and outage/recovery, publisher crash recovery, real consumer replay, live Twelve Data requests, full browser flow with the distributed system, import into the user's STS installation, or lab/cloud deployment.

Included test code and checklists make the remaining checks reproducible; they are not evidence those checks already passed. Run Oracle tests in an isolated development schema with application services stopped, then complete the full-system acceptance checklist before release.

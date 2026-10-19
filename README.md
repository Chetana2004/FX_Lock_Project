<<<<<<< HEAD
<<<<<<< HEAD
# FXLock: code and step-by-step STS guide

This package implements the five-service application in your supplied Member Responsibilities PDF. It is a teaching implementation using simulated customer wallets. All Java source, Maven dependencies, SQL, screens and test code are included; there are no TODO-only service stubs.

## 1. Understand the project structure

| Module | Port | Owner | Main responsibility |
|---|---:|---|---|
| discovery-server | 8761 | M4 / Chetana | Eureka server |
| rate-service | 8081 | M1 / Yash | Market polling and trusted-rate validation |
| quote-service | 8082 | M2 / Reet | Immutable quotes and integrated customer interface |
| settlement-service | 8083 | M3 / Gyanant | Wallets and atomic conversion |
| event-service | 8084 | M4 / Chetana | Migration, outbox publishing, audit/history |
| common | — | Shared | Contracts, security, HTTP configuration, outbox writer |

One Maven parent manages all six modules. `common` is a library, not an application. All four business services use the SAME Oracle schema. Settlement Service updates the shared quote table in its own financial transaction, as required by the PDF. Kafka publishes notifications/history; it never performs the wallet debit or credit.

Event Service is the sole Flyway migration owner. M3 leads schema design; coordinate schema changes with M3 before adding migrations. Run Event Service first against a fresh schema before starting the other business applications.

## 2. Install required software

1. Install **JDK 25** and set JAVA_HOME to its installation directory. Add its `bin` folder to PATH.
2. Install a current **Spring Tools for Eclipse (STS)** build supporting Java 25: https://spring.io/tools/ . An old STS installation may need updating.
3. Install **Maven 3.9.x**, or use STS's embedded Maven with JDK 25 selected for the launch.
4. Install/access **Oracle Database** with a development pluggable database (PDB), and use **SQL Developer** or SQLcl to administer it.
5. Install **Docker Desktop / Docker Compose** for the supplied local Kafka setup. If Kafka is already provided in your lab, use that broker instead.
6. Optionally install **Python 3** for the smoke script.

Check in a NEW terminal:

```text
java -version
javac -version
mvn -version
docker version
```

Java and javac must report 25; Maven must also use that JDK. No external Tomcat installation is necessary.

The source pins Java 25, Spring Boot 3.5.16 and Spring Cloud 2025.0.3. These are a compatible teaching baseline, not a claim to be the newest/supported release line. Before deployment, choose an actively supported Boot/Cloud pair together and retest. Do not change only one version or disable the Cloud compatibility verifier.

References: https://docs.spring.io/spring-boot/3.5/system-requirements.html and https://spring.io/projects/spring-cloud/ .

## 3. Extract and import into STS

1. Extract the ZIP. Keep the parent `fxlock` directory and all child directories together.
2. Open STS and select a workspace.
3. Select **File → Import → Maven → Existing Maven Projects**.
4. Set **Root Directory** to the extracted `fxlock` directory containing the parent `pom.xml`.
5. Select the parent plus every child POM. Click **Finish**.
6. Wait until Maven dependency downloads finish.
7. Go to **Window → Preferences → Java → Installed JREs**.
8. Click **Add → Standard VM**, browse to JDK 25, and select it.
9. If shown, associate **JavaSE-25** with that JDK under **Execution Environments**.
10. Check the projects' **Properties → Java Compiler / Java Build Path** for Java 25.
11. Right-click the parent project → **Maven → Update Project**. Select all modules.
12. Right-click the parent → **Run As → Maven build…**.
13. Enter goals `clean install`. On the **JRE** tab select JDK 25, then run.
14. Confirm **BUILD SUCCESS** in the console.

Do not create five unrelated projects and copy only the Java classes: the shared parent/common dependencies are part of the implementation. Importing the supplied Maven project is the easiest route.

If a dependency cannot download, configure your organization's Maven proxy/repository and trusted CA. Do not disable TLS verification.

## 4. Prepare Oracle once

1. In SQL Developer connect as your development DBA to the intended PDB, not the CDB root.
2. Open `infra/create-user.sql`.
3. Replace `ReplaceThisDevPassword25` with the development password you want to use.
4. Run with **F5 / Run Script**. If the user already exists, use that user instead of running CREATE USER again.
5. Create a normal connection as `fxlock` using that password.
6. Record the host, port and **service name**.

The example JDBC URL is:

```properties
DB_URL=jdbc:oracle:thin:@localhost:1521/FREEPDB1
```

For Oracle XE, your service might be `XEPDB1`; your lab may use a remote host. Confirm with your DBA rather than guessing.

Do not manually run `V1__fxlock.sql` when using Flyway. Event Service applies it automatically once and tracks it in `flyway_schema_history`. Start with an empty development schema. For an existing nonempty schema, agree a migration/baseline plan; do not delete tables or migration history as a routine fix.

V1 creates customers, rate snapshots, quotes, wallets, settlements, ledger entries, idempotency records, audit records, outbox and consumed history. Alice and Bob each receive USD, INR and EUR wallets with 10,000 simulated units per wallet.

## 5. Start Kafka

Open a terminal in the extracted `fxlock` directory:

```bash
docker compose -f infra/compose.yaml up -d
```

This starts local Kafka on port 9092. Oracle is separate; Compose does not install Oracle.

The broker advertises `localhost:9092`, so the Java applications should run directly in STS/on the host. Containerized Java apps or remote deployments require appropriately reachable advertised broker addresses.

Event Service automatically creates:

```text
fx-rate-updated
fx-settlement-completed
fx-system-alert
```

The supplied broker and topic replication factor are one for local development. Shared deployment requires appropriate broker security, replication, retention and monitoring.

## 6. Configure STS application launches

For each business application, use **Run → Run Configurations → Spring Boot App → [application] → Environment**. Set the variables below. Create the launch by selecting its main class if STS has not created one yet.

| Variable | Value / example |
|---|---|
| DB_URL | `jdbc:oracle:thin:@localhost:1521/FREEPDB1` |
| DB_USER | `fxlock` |
| DB_PASSWORD | Password from Step 4 |
| ALICE_PASSWORD | Your chosen Alice password |
| BOB_PASSWORD | Your chosen Bob password |
| OPS_PASSWORD | Your chosen operations password |
| SERVICE_PASSWORD | Your chosen service-to-service password |
| PROVIDER_MODE | `mock` for the first run |
| TWELVE_DATA_API_KEY | Leave empty in mock mode |
| KAFKA_BOOTSTRAP_SERVERS | `localhost:9092` |
| EUREKA_URL | `http://localhost:8761/eureka/` |

Use identical passwords across Rate, Quote, Settlement and Events. Quote Service forwards customer authentication to Settlement, and uses the svc account for internal calls. Eureka needs none of these application passwords in the local configuration.

The `.env.example` file is a template only: STS and Spring Boot DO NOT automatically read `.env`. Enter the variables on every applicable launch, or explicitly export them in the shell that starts Java.

Select JDK 25 on every launch's **JRE** tab. The application main methods set JVM timezone UTC, and the datasource selects UTC for Oracle sessions, to keep JDBC TIMESTAMP mappings consistent. Keep database and service clocks synchronized.

The lab uses HTTP Basic authentication with in-memory users, disabled CSRF, and a local unsecured Eureka server. Use localhost for this exercise. Real deployment requires an agreed identity/session scheme, appropriate CSRF handling, TLS, protected discovery/internal endpoints and external secrets. These are simulated wallets, not a production funds system.

## 7. Start the applications in this order

Use **Run As → Spring Boot App** or the **Boot Dashboard**:

| Order | Main class | Port | Wait for |
|---:|---|---:|---|
| 1 | DiscoveryServerApplication | 8761 | Eureka startup |
| 2 | EventServiceApplication | 8084 | Flyway migration and application startup |
| 3 | RateServiceApplication | 8081 | First scheduled refresh |
| 4 | SettlementServiceApplication | 8083 | Application startup |
| 5 | QuoteServiceApplication | 8082 | Application startup |

Only start one Rate Service instance in this implementation.

Allow time for Eureka registration and client registry fetch. Open http://localhost:8761 and check for RATE-SERVICE, QUOTE-SERVICE, SETTLEMENT-SERVICE and EVENT-SERVICE. Eureka registration comes from the client dependency; no `@EnableEurekaClient` annotation is needed.

If launching JARs instead of STS, run `mvn clean install` first, export the variables, and use a separate terminal for each command:

```bash
java -jar discovery-server/target/discovery-server-1.0.0.jar
java -jar event-service/target/event-service-1.0.0.jar
java -jar rate-service/target/rate-service-1.0.0.jar
java -jar settlement-service/target/settlement-service-1.0.0.jar
java -jar quote-service/target/quote-service-1.0.0.jar
```

These represent five long-running processes, not a sequential one-terminal script.

## 8. Test the customer application

1. Open http://localhost:8082 .
2. Select username `alice`, enter ALICE_PASSWORD and click **Use account**.
3. Click **Refresh wallets**. Credentials are validated on the API call.
4. Choose USD → INR and source amount 100.00.
5. Click **Create 60-second offer**.
6. Read the offered rate, target amount, confidence and countdown.
7. Click **Accept quote** before expiry.
8. Inspect the settlement result and refreshed wallets.
9. Select username `ops` and enter OPS_PASSWORD.
10. Click **Rate health** or **Event & audit history**.

In mock mode, USD/INR is 83.25. With the tutorial spread 0.005 (0.5%), the offered rate is 82.8337500000. Converting 100.00 USD credits **8283.38 INR**, using HALF_EVEN rounding. On a fresh seeded account, USD becomes 9900.00 and INR becomes 18283.38.

Only USD/INR and EUR/USD are configured. Reverse pairs are not automatically synthesized. Add new pairs to rate configuration, mock data, UI and tests together if your team needs them.

## 9. Test the APIs in Postman

Select **Authorization → Basic Auth**. Use Alice's or Bob's configured password for customer endpoints. The authenticated username determines ownership; the API does not trust a submitted customerId.

Create a quote:

```http
POST http://localhost:8082/api/quotes
Content-Type: application/json
```

```json
{"sourceCurrency":"USD","targetCurrency":"INR","amount":"100.00"}
```

Copy `id` from the response. Retrieve it:

```http
GET http://localhost:8082/api/quotes/YOUR_QUOTE_ID
```

Accept it:

```http
POST http://localhost:8082/api/quotes/YOUR_QUOTE_ID/accept
Idempotency-Key: order-001
```

Repeat the SAME acceptance using the SAME key. It must return the committed settlement, even if the quote expired after that original settlement. Using a new key for that already-settled quote also returns the existing result without another debit. Reusing a key for a DIFFERENT quote returns 409.

Other APIs:

| Method | URL | Identity |
|---|---|---|
| GET | `http://localhost:8081/internal/rates?pair=USD%2FINR` | svc or ops |
| GET | `http://localhost:8081/ops/risk` | ops |
| GET | `http://localhost:8082/api/wallets` | alice or bob |
| POST | `http://localhost:8083/api/settlements/QUOTE_ID` with Idempotency-Key | owning customer |
| GET | `http://localhost:8083/api/settlements/SETTLEMENT_ID` | owning customer |
| GET | `http://localhost:8084/ops/history` | ops |
| GET | `/actuator/health` on each business service | public local health |

Bob must receive 404 when requesting Alice's quote or result. Customer credentials cannot read global operations history. Registry/health UP does not prove live data freshness; inspect rate risk too.

## 10. Locate the required code

All paths below start at the extracted `fxlock` directory. Within Java modules, the package directory is `src/main/java/com/fxlock/`.

| Requirement | Source file |
|---|---|
| Shared Maven versions | `pom.xml` |
| Eureka server | `discovery-server/.../discoveryserver/DiscoveryServerApplication.java` |
| Shared DTOs and event envelope | `common/.../common/Contracts.java` |
| Transaction-required outbox writer | `common/.../common/Outbox.java` |
| Authentication and roles | `common/.../common/SecurityConfig.java` |
| Provider client, scheduler, cache | `rate-service/.../rate/RateService.java` |
| Rate freshness and anomaly comparison | `rate-service/.../rate/RatePolicy.java` |
| Quote creation/retrieval and acceptance proxy | `quote-service/.../quote/QuoteController.java` |
| BigDecimal spread/rounding/confidence | `quote-service/.../quote/Pricing.java` |
| Customer/operations page | `quote-service/src/main/resources/templates/index.html` |
| Browser logic and style | `quote-service/src/main/resources/static/app.js`, `style.css` |
| Atomic financial transaction | `settlement-service/.../settlement/SettlementService.java` |
| Failure audit after rollback | `settlement-service/.../settlement/AttemptAudit.java` |
| Shared schema and seed | `event-service/src/main/resources/db/migration/V1__fxlock.sql` |
| Outbox claim, publish, retry, recovery | `event-service/.../event/OutboxWorker.java` |
| Consumer deduplication | `event-service/.../event/HistoryConsumer.java` |
| Audit and publication API | `event-service/.../event/HistoryController.java` |
| Three Kafka topics | `event-service/.../event/KafkaConfig.java` |
| Reconciliation evidence | `infra/reconciliation.sql` |
| HTTP smoke test | `scripts/smoke.py` |
| CI | `.github/workflows/build.yml` |

## 11. Understand the important rules

**Rate safety.** A snapshot's age comes from the provider's observation timestamp, never the local receipt time. Same/older, future, stale, invalid or anomalous observations do not replace the previous trusted snapshot. That snapshot can support quotes only until its ORIGINAL age reaches 90 seconds. Snapshot and outbox insert commit together before the cache advances. HTTP connect/read timeouts are bounded; raw provider error URLs are not logged because they may include the key.

The anomaly threshold is a tutorial **5%**, configurable in Rate Service. During provider trouble/anomaly, an older trusted snapshot can still support quotes while fresh, with reduced confidence. If the team wants all anomalies to pause quote issuance, agree and implement that additional policy explicitly.

Rate cache and current risk flags are local. Startup reloads the last trusted snapshot with provider health false until a new provider observation validates; it does not renew the snapshot age. Durable risk state and coordinated ingestion are needed for multi-instance deployment. Proposed anomaly approval/pause controls are absent because their behavior is unspecified in the PDF.

**Quote rules.** Quotes validate amount scale, source/target currencies, differing currencies and bounds. Rate freshness is independently rechecked before insert. Stored prices and amounts never change when later market data arrives. The tutorial uses 0.5% spread, bounds 1–10,000 source units, two-decimal USD/INR/EUR wallets, ten-decimal offered rates and HALF_EVEN rounding. These rules require team agreement; other currencies need currency-specific scales.

Confidence uses the PDF's weights with explicit tutorial component curves: freshness `40 × max(0,1 − ageSeconds/90)`; anomaly 20 when clear; provider 20 when healthy; stability `20 × clamp(1 − movementFraction/0.05,0,1)`. The PDF specifies weights, not these curves. Align the stability denominator if you change the anomaly threshold.

The UI countdown uses the browser clock, while the server decides whether acceptance is valid. The page keeps its idempotency key in memory. After network response loss, retry the same key; API replay still works after the visible countdown expires if a settlement already committed. Browser-reload recovery needs an additional durable customer-order flow.

**Settlement integrity.** The transaction locks the owning quote, checks prior results, resolves wallet IDs and explicitly locks wallets in ascending ID order. After locks are acquired, it checks idempotency again, reads Oracle's UTC clock, validates expiry/status/funds, and uses the STORED quote amounts. Source debit, target credit, two ledger entries, settlement, quote status, idempotency mapping, success audit and completion outbox commit together. Failure rolls them all back. Failed-attempt audit is then attempted in a separate REQUIRES_NEW transaction. Database outages can prevent even failure auditing; this is logged.

The quote status is changed through direct shared-database access, not a Quote Service HTTP call. No new rate is fetched during settlement. Unique constraints on quote settlement and customer/key reinforce concurrency safety.

**Event reliability.** Outbox claims use `FOR UPDATE SKIP LOCKED` in a short Oracle transaction; Kafka I/O occurs after claim commit. Rows become PUBLISHED only after broker acknowledgment. A claim token fences status updates. Failures retry after ten seconds; crashed INFLIGHT claims recover after a five-minute lease.

A publisher crash after send but before status update can duplicate a Kafka event. Publication is at least once. Consumer history has a primary key on eventId; its DB effect commits before Kafka offset acknowledgment. Future consumers must independently deduplicate their own effects. This is not exactly-once delivery across Kafka and Oracle, nor a guarantee of strict aggregate ordering across retries.

Malformed/unsupported events deliberately retry indefinitely rather than being discarded. A dead-letter queue, poison-message process, retention policy, richer pagination and operational alerting remain release work. Operations views show the latest 100 rows per category.

## 12. Run tests and gather evidence

Default checks:

```bash
mvn clean verify
```

AccessControlTest checks authentication, principal identity and customer/ops/service boundaries. RateSafetyTest checks empty cache, nonpositive rates, repeated timestamps, stale/future/out-of-order/wrong-pair data, anomalies, malformed HTTP, timeout and restart. PricingTest checks decimal spread/rounding, freshness, confidence and zero output.

OracleSettlementTest is disabled by default. To run it:

1. Provision a SEPARATE development schema using V1 via Event Service.
2. Stop running application services using that schema, especially the event worker.
3. Set DB variables to that isolated schema and set the four application password variables.
4. In PowerShell run:

```powershell
$env:RUN_ORACLE_TESTS = "true"
mvn -pl settlement-service -am test
```

Or bash:

```bash
RUN_ORACLE_TESTS=true mvn -pl settlement-service -am test
```

The six Oracle tests cover 100 parallel accepts, competing spends, failed-credit rollback through Oracle NUMBER overflow, response-loss replay/conflicting keys, ownership, and expiry during a wallet lock wait. They create temporary fixture customers and clean up their financial rows. Do not run against an actively used shared/demo database. H2 is not a substitute for Oracle row-locking evidence.

With the whole application running and ALICE_PASSWORD exported:

```bash
python scripts/smoke.py
```

The smoke script spends 10 simulated USD each run and checks quote creation, committed replay and stored status. Run `infra/reconciliation.sql` in SQL Developer afterwards; every query should return zero rows.

Capture these additional lab scenarios:

| Scenario | Procedure | Expected result |
|---|---|---|
| Kafka outage/recovery | `docker compose -f infra/compose.yaml stop kafka`; create/settle; then `start kafka` | Financial commit succeeds, pending events retained, later published/history recorded |
| Publisher crash window | Break after Kafka send acknowledgment but before PUBLISHED update; terminate and restart | Lease recovery retries; one history effect per eventId |
| Consumer replay | Replay an identical envelope in isolated Kafka | One history record for that ID |
| Rate restart | Age stored snapshot >90 seconds; restart with provider unavailable | New quotes refused despite cache reload |
| Fixed quote price | Create quote, change trusted market rate, accept original quote | Original stored amounts used |
| Quote expiry | Wait >60 seconds before first acceptance | 409, no financial changes |
| Cross-customer access | Read Alice's record as Bob | 404, no disclosure/change |
| Clean setup | Fresh schema → migration → startup → smoke → reconciliation | Repeatable setup and zero reconciliation findings |

Manual scenarios and included integration tests are a plan until executed. See VALIDATION.md for checks actually performed on this package.

## 13. Troubleshoot and recover

| Symptom | Check / action |
|---|---|
| release version 25 not supported | Select JDK 25 for STS projects AND Maven/application launches |
| Could not resolve placeholder DB_PASSWORD | Add variable to that specific launch; `.env` is not auto-loaded |
| ORA-12514 / ORA-12541 | Check listener, port and PDB service name |
| ORA-01017 | Check Oracle username/password |
| ORA-00942 | Finish Event Service migration; confirm all services use same schema |
| Flyway checksum/nonempty schema error | Inspect state; add a migration/baseline plan, do not delete history |
| Quote returns 503 | Check pair, observation freshness, provider mode/key, Eureka and shared svc password |
| Live provider repeatedly returns old data | Check subscription, market hours and timestamp/update frequency; polling cannot make delayed data fresh |
| Kafka unavailable | Restore broker; retain outbox records and wait for retry |
| INFLIGHT after crash | Restart Event Service and wait up to five minutes for lease recovery |
| Invalid event blocks consumer | Repair/upgrade producer or consumer; reconcile before any deliberate offset intervention |
| Settlement timeout / response loss | Retry same quote with same idempotency key |
| Database unavailable | Restore database, restart services, reconcile before resuming writes |
| Port occupied | Stop previous STS launch before restarting |

Never mark outbox rows published merely to hide an error, or delete financial rows as a routine recovery procedure.

## 14. Member 4 checklist for Chetana

Start with discovery-server, parent POM, shared Contracts/Outbox, schema migration, then KafkaConfig → OutboxWorker → HistoryConsumer → HistoryController. Check all application names and Eureka settings with the other members. Establish one shared event contract and agreed shared quote-table access. Run CI/unit checks, provision the lab, collect integration/reconciliation evidence and document the final release.

The package includes local Kafka configuration and local/shared endpoint variables; it is not a lab-specific cloud deployment manifest. Remaining team decisions include thresholds, spread, limits, confidence curves, currency support, anomaly controls, identity, supported dependency versions, retention and deployment environment. These must be resolved and tested before a final release.
=======
=======
>>>>>>> 9f5bd64efa8bee475317ae0352b6001cda302168
# FXLock Project

Foreign-exchange rates change continuously, creating uncertainty in cross-border payments.

A customer may be shown a conversion amount based on the current FX rate, but the rate can change
before the customer confirms the payment. This creates two conflicting needs.

1) The customer wants a stable and predictable payment amount.
2) The organization wants to avoid excessive exposure to outdated FX rates.

The payment process may also depend heavily on an external FX-rate provider.
Calling the provider for every customer request can introduce latency, rate-limit issues, and availability
problems.

In addition, duplicate clicks, network retries, or concurrent requests may attempt to 
process the same payment more than once, creating a risk of duplicate or inconsistent settlement.

The core problem is threfore to support fast and reliable corss-border FX payments while
maintaining price certainty, rate freshness, settlement correctness and operational resilience.

<<<<<<< HEAD
>>>>>>> 9f5bd64efa8bee475317ae0352b6001cda302168
=======
>>>>>>> 9f5bd64efa8bee475317ae0352b6001cda302168

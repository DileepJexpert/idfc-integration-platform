# IDFC Java 25 and Spring Boot 4.1 Migration Runbook

**Document type:** Architecture and execution runbook  
**Target estate:** IDFC Spring Boot Java microservices  
**Strategic target:** JDK 25 + Spring Boot 4.1.0  
**Document date:** 24 July 2026  
**Owner:** Java Platform / Architecture Team  
**Status:** Draft for internal approval and execution  

---

## 1. Purpose

This document is the single execution guide for migrating IDFC Java microservices from:

- JDK 21 to JDK 25
- Spring Boot 3.5.x to Spring Boot 4.1.0
- Tomcat 10.1.x or manually overridden Tomcat versions to the Spring Boot 4 / Tomcat 11 baseline

It also defines how internal Checkmarx findings, dependency overrides, vulnerability remediation, testing evidence, production rollout and rollback must be handled.

This migration is not complete when a service merely compiles or starts. A service is complete only after it passes the security, compatibility, functional, performance, observability, canary and rollback gates in this document.

---

## 2. Final target baseline

| Component | Target |
|---|---|
| Java runtime | JDK 25, IDFC-approved vendor and patch |
| Spring Boot | 4.1.0 |
| Spring Framework | Boot-managed 7.0.x |
| Servlet specification | Jakarta Servlet 6.1 |
| Embedded Tomcat | Boot-managed Tomcat 11.0.22 by default |
| Security-approved Tomcat patch | Latest IDFC-approved Tomcat 11.0.x; use an override only when supported by a Checkmarx/vendor finding |
| Maven | IDFC-approved Maven 3.9.x |
| Gradle | 9.1 or later when Gradle itself runs on JDK 25 |
| Gradle Wrapper | Must be committed to Git |
| JaCoCo | Java 25-compatible release, minimum 0.8.14 |
| Preview Java features | Not allowed in production |
| Default GC | Keep the current collector during migration |
| Virtual threads | Separate optimisation workstream |
| Native image | Out of scope unless explicitly approved |
| Rollback | Previous JDK 21 / Spring Boot 3 image retained and tested |

### Important baseline rule

Do not combine the following changes in the same service release unless the Architecture Review explicitly approves it:

- JDK migration
- Spring Boot major upgrade
- Functional application changes
- GC change
- Virtual-thread adoption
- Database-driver major upgrade
- Kafka broker migration
- Kafka message-format change
- OpenTelemetry architecture change
- Large refactoring
- Container operating-system change

The parent platform release will contain JDK 25 and Spring Boot 4.1 changes. Service teams must avoid unrelated feature work in the same pull request.

---

## 3. Verified framework facts

As of 24 July 2026:

- Spring Boot 4.1.0 supports Java 17 through Java 26.
- Spring Boot 4.1.0 supports Tomcat 11.0.x and Servlet 6.1.
- Spring Boot 4.1.0 manages Tomcat 11.0.22 by default.
- Spring Boot 4 uses Spring Framework 7 and Jakarta EE 11.
- Spring Boot 4 has a more modular dependency and starter structure.
- Spring Boot recommends first moving applications to the latest Spring Boot 3.5.x before the Boot 4 migration.
- Gradle can run on JDK 25 from Gradle 9.1 onward.
- Apache Tomcat security fixes are normally consumed by upgrading to a fixed Tomcat release rather than applying a vendor binary patch.

These values must be revalidated before the final production rollout because patch releases and vulnerability findings change over time.

---

## 4. Migration strategy

Use three controlled stages.

### Stage A — Prepare the current estate

Bring every candidate service to the current IDFC Spring Boot 3.5 parent and confirm:

- It builds cleanly on JDK 21.
- Deprecated Spring Boot 3 APIs are identified.
- Existing dependency overrides are documented.
- Existing Checkmarx findings and exceptions are known.
- Functional and performance baselines are recorded.
- The current production image digest is recorded for rollback.

### Stage B — Create and qualify the new platform parent

Create an IDFC Spring Boot 4 parent release candidate:

```text
com.idfcfirstbank:boot-parent:2.0.0-RC1
```

The release candidate must use:

```text
JDK 25
Spring Boot 4.1.0
Tomcat 11.0.x
```

Publish the final parent only after pilot services pass all gates:

```text
com.idfcfirstbank:boot-parent:2.0.0
```

### Stage C — Migrate services in waves

Use the following migration waves:

1. Platform test application
2. Low-risk stateless REST services
3. Standard REST and data services
4. Kafka, batch, scheduler and large-payload services
5. Customer-critical, payment and regulatory services

Do not begin a new wave until the prior wave has at least two stable business days in production.

---

## 5. Scope and non-scope

### In scope

- JDK 25 runtime and compilation
- Spring Boot 4.1.0
- Spring Framework 7
- Jakarta EE 11 compatibility
- Tomcat 11
- Parent POM / Gradle convention updates
- Shared IDFC starter compatibility
- Jackson migration
- Spring Security compatibility
- Kafka client and Spring Kafka compatibility
- Redis, Aerospike, JDBC and S3 compatibility
- OpenTelemetry, Micrometer, Jaeger, Prometheus and logging compatibility
- Checkmarx and container vulnerability scans
- Performance comparison and production canary
- JDK 21 rollback

### Out of scope

- New business functionality
- Virtual threads
- Compact object headers
- ZGC or Shenandoah adoption
- Native image
- Spring Cloud migration unless required by compatibility
- Kafka broker upgrade
- Database schema redesign
- API contract redesign
- Replacement of observability products

Any item moved into scope requires a separate architecture decision.

---

## 6. Required roles

| Role | Responsibility |
|---|---|
| Architect | Accountable for scope, target baseline, exceptions, gates and Go/No-Go |
| Java Platform Team | Parent POM, shared libraries, dependency management and migration support |
| Service Team | Service code changes, tests, evidence and production support |
| DevOps / Cloud | Build images, runtime images, GoCD templates, EKS deployment and rollback |
| QA | Functional, contract and regression tests |
| Performance QA | Baseline, load, stress and soak tests |
| Security | Checkmarx review, CVE applicability, exceptions and final approval |
| Observability Team | Metrics, logs, traces, alerts and dashboard verification |
| Product / Business Owner | Business-window and service-risk approval where required |

---

## 7. Pre-migration inventory

Create one inventory row for every service.

| Field | Value |
|---|---|
| Service name | |
| Repository | |
| Service owner | |
| Business criticality | Low / Medium / High / Critical |
| Current Java version | |
| Current Spring Boot version | |
| Current parent version | |
| Build tool | Maven / Gradle |
| Current Tomcat version | |
| Tomcat override present | Yes / No |
| Kafka used | Yes / No |
| Redis used | Yes / No |
| Aerospike used | Yes / No |
| JDBC/database used | Yes / No |
| S3 used | Yes / No |
| OAuth2/Ory Hydra used | Yes / No |
| mTLS used | Yes / No |
| Java agents | |
| Native libraries/JNI | |
| Current Checkmarx findings | |
| Current security exceptions | |
| Current production image digest | |
| JDK 21 performance baseline available | Yes / No |
| Migration wave | |

No service enters a migration wave with an incomplete inventory.

---

## 8. Security and Checkmarx governance

### 8.1 Security finding record

For every Checkmarx, SCA, container scan or dependency finding, record:

| Field | Required value |
|---|---|
| Internal finding ID | |
| Scan type | SAST / SCA / container / other |
| Scan date | |
| CWE | |
| CVE | |
| Affected coordinate | Example: `org.apache.tomcat.embed:tomcat-embed-core` |
| Dependency path | Direct or transitive path |
| Vulnerable version range | |
| First fixed version | |
| Runtime applicability | Applicable / Not applicable / Unknown |
| Internet/internal reachability | |
| Selected remediation | |
| Security owner | |
| Exception expiry, if any | |
| Clean rescan reference | |

> Dependency CVEs are normally identified through software-composition or dependency scanning. If the internal workflow labels the result as SAST, still capture the exact dependency coordinate and CVE.

### 8.2 Override policy

A dependency version override is allowed only when all of the following are present:

- Internal security finding or vendor compatibility requirement
- Exact dependency coordinate
- Exact approved version
- Named owner
- Review date
- Clean scan or approved exception
- Regression-test evidence

An override must not be added merely because it is the newest available version.

An override must not be removed merely because it differs from the Spring Boot BOM.

### 8.3 Override comment format

Use this format in the parent POM:

```xml
<!--
  IDFC SECURITY OVERRIDE
  Finding ID : <CHECKMARX-ID>
  CVE/CWE    : <CVE-or-CWE>
  Component  : <groupId:artifactId>
  Approved   : <YYYY-MM-DD>
  Owner      : <team/name>
  Review by  : <YYYY-MM-DD>
-->
```

### 8.4 Tomcat security rule

Spring Boot 4.1.0 manages Tomcat 11.0.22 by default.

The initial RC must follow one of these two patterns.

#### Pattern 1 — Boot-managed Tomcat

Use this when the internal scan is clean:

```xml
<!-- No tomcat.version override -->
```

#### Pattern 2 — IDFC security-approved Tomcat patch

Use this when Checkmarx or an Apache advisory requires a later fixed Tomcat 11.0.x patch:

```xml
<properties>
    <!--
      IDFC SECURITY OVERRIDE
      Finding ID : <CHECKMARX-ID>
      CVE/CWE    : <CVE-or-CWE>
      Component  : org.apache.tomcat.embed
      Approved   : <YYYY-MM-DD>
      Owner      : Java Platform Team
      Review by  : <YYYY-MM-DD>
    -->
    <tomcat.version>11.0.24</tomcat.version>
</properties>
```

`11.0.24` is an example based on the current available Tomcat 11 patch at the time of writing. The final value must be the version approved by IDFC Security on the execution date.

All of these modules must resolve to one patch version:

```text
tomcat-embed-core
tomcat-embed-el
tomcat-embed-websocket
tomcat-embed-jasper, where used
```

Validation:

```bash
mvn -q dependency:tree -Dincludes=org.apache.tomcat.embed
```

or:

```bash
./gradlew dependencyInsight \
  --dependency tomcat-embed-core \
  --configuration runtimeClasspath
```

Reject the build when mixed Tomcat versions are present.

---

## 9. Parent POM target

Use the following as the starting structure for the IDFC Boot 4 parent.

```xml
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>4.1.0</version>
    <relativePath/>
</parent>

<groupId>com.idfcfirstbank</groupId>
<artifactId>boot-parent</artifactId>
<version>2.0.0-RC1</version>
<packaging>pom</packaging>

<properties>
    <java.version>25</java.version>
    <maven.compiler.release>25</maven.compiler.release>

    <!-- Use a Java 25-compatible JaCoCo version. -->
    <jacoco.version>0.8.15</jacoco.version>

    <!-- Add only after Security approval. -->
    <!-- <tomcat.version>11.0.24</tomcat.version> -->
</properties>
```

### Parent POM rules

1. Inherit from `spring-boot-starter-parent` only once.
2. Do not import `spring-boot-starter-parent` in `dependencyManagement`.
3. Let Spring Boot manage Spring Framework, Jackson, Tomcat, Netty, Reactor, Micrometer and Spring Security unless an approved exception exists.
4. Do not mix Spring Boot 3 and Spring Boot 4 modules.
5. Do not keep `javax.*` APIs where a Jakarta EE 11 equivalent is required.
6. Do not introduce preview features.
7. Do not force an independent Spring Framework version.
8. Do not add a blanket `--add-opens` workaround in the parent.
9. Do not suppress Java restricted-native-access warnings globally.
10. Publish an RC before the final parent.

### Maven enforcer

Add an enforcer rule:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-enforcer-plugin</artifactId>
    <executions>
        <execution>
            <id>enforce-platform</id>
            <goals>
                <goal>enforce</goal>
            </goals>
            <configuration>
                <rules>
                    <requireJavaVersion>
                        <version>[25,26)</version>
                    </requireJavaVersion>
                    <requireMavenVersion>
                        <version>[3.9,4)</version>
                    </requireMavenVersion>
                    <dependencyConvergence/>
                </rules>
            </configuration>
        </execution>
    </executions>
</plugin>
```

If `dependencyConvergence` fails because of an approved platform exception, document and narrowly exclude only the known dependency path. Do not disable convergence for the entire estate.

---

## 10. Gradle target

For Gradle services:

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '4.1.0'
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

tasks.withType(JavaCompile).configureEach {
    options.release = 25
}
```

### Gradle version

When Gradle itself runs on JDK 25, use Gradle 9.1 or later.

Check:

```bash
./gradlew --version
```

### Gradle Wrapper files

Commit all wrapper files:

```text
gradlew
gradlew.bat
gradle/wrapper/gradle-wrapper.jar
gradle/wrapper/gradle-wrapper.properties
```

Do not commit:

```text
.gradle/
build/
```

Preserve the Unix executable bit:

```bash
chmod +x gradlew
git add gradlew
git ls-files --stage gradlew
```

Expected mode:

```text
100755
```

Pipelines must use:

```bash
./gradlew clean build
```

not:

```bash
gradle clean build
```

---

## 11. Spring Boot 4 code-impact assessment

### 11.1 Upgrade from latest Boot 3.5 first

Before changing the service to Boot 4:

- Adopt the latest approved IDFC Spring Boot 3.5 parent.
- Resolve compiler deprecation warnings.
- Remove use of APIs deprecated in Boot 3.
- Record existing configuration-property warnings.
- Capture API and JSON contract baselines.

### 11.2 Jakarta EE 11

Search for legacy namespaces:

```bash
grep -R "import javax\." src/main src/test
grep -R "javax\." pom.xml build.gradle settings.gradle
```

Typical changes:

```text
javax.servlet.*       -> jakarta.servlet.*
javax.validation.*    -> jakarta.validation.*
javax.persistence.*   -> jakarta.persistence.*
javax.annotation.*    -> jakarta.annotation.*
javax.xml.bind.*      -> jakarta.xml.bind.*
```

Do not perform blind replacement. Some non-Jakarta `javax` packages remain part of Java SE.

### 11.3 Starter changes

Boot 4 modularised starters and test infrastructure.

Review:

```text
spring-boot-starter-web
spring-boot-starter-test
spring-security-test
Flyway
Liquibase
Kafka
Actuator
OpenTelemetry
Micrometer
```

The older `spring-boot-starter-web` is deprecated in favour of:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-webmvc</artifactId>
</dependency>
```

For a controlled first pilot, classic starters may temporarily reduce the migration surface:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-classic</artifactId>
</dependency>
```

and:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-test-classic</artifactId>
    <scope>test</scope>
</dependency>
```

Classic starters are a temporary bridge. The final target must use focused Boot 4 starters.

### 11.4 Configuration-property migration

Temporarily add:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-properties-migrator</artifactId>
    <scope>runtime</scope>
</dependency>
```

Run the service, resolve all reported property changes, then remove the migrator before production.

### 11.5 Jackson 3

Spring Boot 4 uses Jackson 3 as the primary JSON stack.

Search:

```bash
grep -R "com.fasterxml.jackson" src/main src/test
grep -R "ObjectMapper" src/main src/test
grep -R "Jackson2ObjectMapperBuilder" src/main src/test
grep -R "@JsonComponent" src/main src/test
```

Mandatory tests:

- Request JSON
- Response JSON
- Null handling
- Date/time formatting
- BigDecimal formatting
- Enum formatting
- Unknown-property behaviour
- Polymorphic types
- Custom serializers/deserializers
- Kafka JSON messages
- Stored JSON documents
- Error-response JSON

The following is allowed only as a temporary migration bridge:

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-jackson2</artifactId>
</dependency>
```

Any use of the Jackson 2 bridge must have:

- An owner
- A removal ticket
- An expiry date
- JSON contract tests

### 11.6 Test annotations

Search:

```bash
grep -R "@MockBean" src/test
grep -R "@SpyBean" src/test
```

Migrate tests to Spring Framework-supported Mockito bean overrides such as:

```java
@MockitoBean
@MockitoSpyBean
```

Review semantics rather than performing a blind text replacement.

### 11.7 Spring Retry

Spring Boot 4 no longer manages Spring Retry.

Search:

```bash
grep -R "spring-retry" pom.xml build.gradle
grep -R "@Retryable" src/main src/test
grep -R "RetryTemplate" src/main src/test
```

Decision:

- Prefer Spring Framework retry capabilities where practical.
- Where Spring Retry remains necessary, declare and approve its version explicitly.
- Do not leave an unmanaged transitive version.

### 11.8 Kafka

Mandatory checks:

- Producer and consumer startup
- Serializer/deserializer behaviour
- Header propagation
- Manual acknowledgement
- Transactions
- Error handlers
- Retry topics
- Dead-letter topics
- Consumer group rebalance
- Kafka Streams customisation
- Consumer lag
- Broker compatibility with IDFC MSK
- Message contract compatibility

Review removed or changed properties, including retry-topic backoff properties.

Do not change message schemas during this migration.

### 11.9 Spring Security and OAuth2

Test:

- Ory Hydra token validation
- JWT signature validation
- Issuer/audience validation
- OAuth2 client flow
- Resource-server flow
- Role/authority mapping
- Method security
- CORS
- CSRF where applicable
- Security filter order
- Authentication failure responses
- mTLS
- Keystore/truststore loading
- Certificate rotation
- Expired/invalid certificate behaviour

### 11.10 Custom IDFC starters

Do not support Boot 3 and Boot 4 from the same starter artifact unless explicitly proven safe.

Version shared starters separately:

```text
idfc-observability-starter:3.x -> Spring Boot 3
idfc-observability-starter:4.x -> Spring Boot 4

idfc-security-starter:3.x      -> Spring Boot 3
idfc-security-starter:4.x      -> Spring Boot 4
```

Each Boot 4 starter requires its own compatibility and security scan.

---

## 12. JDK 25 assessment

### 12.1 Build verification

Maven:

```bash
java -version
mvn -version
mvn -U clean verify
```

Gradle:

```bash
java -version
./gradlew --version
./gradlew clean build
```

### 12.2 Internal JDK API scan

For compiled classes:

```bash
jdeps --jdk-internals target/classes
```

For a packaged JAR:

```bash
jdeps --multi-release 25 --jdk-internals target/*.jar
```

No unapproved use of JDK internal APIs is allowed.

### 12.3 Deprecated Java API scan

```bash
jdeprscan --release 25 target/classes
```

Every finding must be:

- Fixed
- Accepted with a ticket
- Proven to originate only from a third-party library and assigned to an owner

### 12.4 Native access

Review warnings related to:

- `System.load`
- `System.loadLibrary`
- JNI
- JNA
- PKCS#11
- HSM integration
- Profilers
- APM agents
- Security agents
- Gradle Tooling API

Do not globally suppress warnings without identifying the library and owner.

### 12.5 JVM flags

Inventory flags from:

```text
JAVA_TOOL_OPTIONS
JDK_JAVA_OPTIONS
Dockerfile
Helm values
ConfigMaps
GoCD variables
startup scripts
environment overrides
```

Review:

```text
--add-opens
--add-exports
--enable-native-access
--enable-preview
-Xms
-Xmx
-XX:MaxRAMPercentage
-XX:InitialRAMPercentage
-XX:MaxMetaspaceSize
-XX:+UseG1GC
-XX:+UseZGC
-Xlog
-javaagent
```

Rules:

- No preview features.
- No new GC during migration.
- Every `--add-opens` and `--add-exports` has an owner and removal plan.
- Every Java agent must be certified on JDK 25.
- Keep existing memory sizing for the first performance comparison.

---

## 13. Dependency analysis

Generate and archive these files for every pilot service.

### Maven

```bash
mkdir -p migration-evidence

mvn -q help:effective-pom \
  -Doutput=migration-evidence/effective-pom.xml

mvn -q dependency:tree -Dverbose \
  -DoutputFile=migration-evidence/dependency-tree.txt

mvn -q dependency:tree \
  -Dincludes=org.apache.tomcat.embed \
  -DoutputFile=migration-evidence/tomcat-tree.txt
```

### Gradle

```bash
mkdir -p migration-evidence

./gradlew dependencies \
  > migration-evidence/dependencies.txt

./gradlew dependencyInsight \
  --dependency tomcat-embed-core \
  --configuration runtimeClasspath \
  > migration-evidence/tomcat-insight.txt
```

Review these high-risk components:

- Tomcat
- Jackson
- Byte Buddy
- ASM
- Mockito
- Lombok
- MapStruct
- JaCoCo
- Hibernate
- Netty
- Reactor
- Kafka clients
- Aerospike client
- Redis/Lettuce/Jedis
- JDBC drivers
- Bouncy Castle
- PKCS#11/HSM libraries
- OpenTelemetry
- Micrometer
- Logging libraries
- APM/security/java agents

Reject:

- Mixed major versions
- Duplicate logging implementations
- Mixed Jakarta and legacy Java EE APIs
- Unapproved snapshots
- Unapproved alpha dependencies
- Unowned overrides
- Known vulnerable versions without an exception

---

## 14. Build and container baseline

### 14.1 Build image

The build image must contain:

- Approved JDK 25
- Approved Maven or Gradle wrapper support
- Internal repository configuration
- CA certificates
- Security-scan integration
- No unnecessary package manager cache

The pipeline must print:

```bash
java -version
mvn -version
```

or:

```bash
java -version
./gradlew --version
```

### 14.2 Runtime image

Requirements:

- Approved JDK/JRE 25 vendor
- Immutable tag or digest
- Non-root user
- IDFC CA trust
- Timezone policy
- No build tools
- Vulnerability scan
- SBOM
- Read-only filesystem where supported
- Correct EKS liveness/readiness probes

Example:

```dockerfile
FROM <idfc-approved-jdk25-build-image> AS build
WORKDIR /workspace
COPY . .
RUN ./mvnw -U clean verify

FROM <idfc-approved-jdk25-runtime-image>
WORKDIR /application
COPY --from=build /workspace/target/*.jar app.jar
USER <non-root-user>
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### 14.3 Rollback image

The existing JDK 21 / Boot 3 production image must be:

- Preserved by digest
- Deployable without rebuild
- Present in the production registry
- Referenced in the rollout ticket
- Tested before production migration

---

## 15. Mandatory functional test pack

Run the relevant sections for each service.

### Core application

- Application context starts
- All profiles start
- Configuration binding works
- Health endpoints work
- Readiness and liveness work
- Graceful shutdown works
- Error handling works
- Scheduled jobs work
- Async execution works
- Thread pools are stable

### REST and web

- All API contracts
- Validation
- Header handling
- Query/path parameters
- Multipart upload
- Large request/response
- Error response format
- Compression
- HTTP/2 where enabled
- Connection timeout
- Client abort
- Request limits
- CORS
- WebSocket where used

### Security

- OAuth2
- JWT
- mTLS
- Certificates
- Keystore/truststore
- Encryption/decryption
- Digital signing
- Role and authority mapping
- Invalid-token behaviour
- Expired-token behaviour

### Kafka

- Produce
- Consume
- Retry
- Dead-letter
- Transaction
- Rebalance
- Deserialization failure
- Consumer lag
- Shutdown/restart

### Data

- Aerospike read/write
- Redis read/write/TTL
- JDBC connection and transaction
- Connection-pool exhaustion
- Database failover where testable
- S3 upload/download
- Serialization compatibility

### Observability

- ELK/Kibana logs
- Prometheus metrics
- Micrometer metrics
- Jaeger traces
- OpenTelemetry propagation
- BTO integration
- Correlation IDs
- Error tags
- Dashboard and alerts
- No sensitive-data leakage

---

## 16. Large-payload and memory test

Services processing large JSON, CKYC/CERSAI or document payloads require a dedicated test.

Test at:

```text
Current typical size
Current maximum size
25 MB
Expected future maximum
Two concurrent maximum-size requests
Configured production concurrency
```

Measure:

- Heap
- Process RSS
- Allocation rate
- GC
- Deserialization time
- Response time
- Thread count
- Pod restart
- OOM
- Downstream timeout
- Backpressure

Do not approve a heap increase as the only remediation for an unbounded payload.

Where possible, assess:

- Streaming
- Incremental parsing
- File-backed buffering
- Payload limits
- Concurrency limits
- Request rejection
- Segmentation
- Timeouts
- Circuit breaking

---

## 17. Performance test

Compare the same service commit on:

```text
Baseline: JDK 21 + current Spring Boot 3 parent
Target:   JDK 25 + Spring Boot 4.1 parent
```

Keep constant:

- Source commit
- Dataset
- Pod count
- CPU/memory limits
- JVM memory flags
- Traffic profile
- Test duration
- Downstream environment
- Kafka partitions
- Database pool size

Capture:

| Metric | JDK 21 / Boot 3 | JDK 25 / Boot 4 | Difference | Gate |
|---|---:|---:|---:|---|
| p50 latency | | | | |
| p95 latency | | | | |
| p99 latency | | | | |
| Throughput | | | | |
| Error rate | | | | |
| CPU | | | | |
| RSS memory | | | | |
| Steady heap | | | | |
| Allocation rate | | | | |
| GC pause | | | | |
| Startup time | | | | |
| Readiness time | | | | |
| Thread count | | | | |
| Kafka lag | | | | |

Initial acceptance gates:

- No functional error increase
- p95/p99 regression no more than 5% without approval
- CPU or RSS regression no more than 10% without approval
- No sustained heap growth
- No OOM or pod restart
- No increasing Kafka lag
- No connection-pool exhaustion
- No trace or metric loss

Critical/high-throughput services require a 48-hour soak test.

---

## 18. Checkmarx and security scan sequence

Run scans in this order:

1. Current JDK 21 / Boot 3 baseline
2. Parent RC dependency scan
3. Pilot service source scan
4. Pilot service dependency/SCA scan
5. Container image scan
6. Runtime configuration review
7. Post-remediation rescan
8. Production artifact scan

For every finding, classify:

```text
New finding introduced by migration
Existing finding
Resolved finding
False positive
Not applicable
Accepted risk
Blocked
```

Migration is blocked when:

- A new Critical or High finding is unresolved
- The Tomcat version is not security-approved
- A vulnerable transitive dependency has no remediation or exception
- A security exception has expired
- The final container image has an unapproved Critical/High finding
- Scan evidence is missing

---

## 19. Pilot service selection

Select at least three pilot services.

### Pilot 1 — Stateless REST service

Must cover:

- Spring MVC
- Validation
- Security
- Actuator
- Logging
- Metrics
- JSON

### Pilot 2 — Kafka service

Must cover:

- Producer
- Consumer
- Retry
- DLT
- Transactions where used
- Observability
- Graceful shutdown

### Pilot 3 — Data-heavy service

Must cover:

- Aerospike or JDBC
- Redis
- Large payloads where possible
- TLS/mTLS
- External integration
- Resource usage

Prefer a fourth pilot when shared IDFC starters or unusual Java agents are heavily used.

---

## 20. Execution schedule

### Week 0 — Architecture and security preparation

- Approve target baseline
- Complete service inventory
- Reconcile Checkmarx findings
- Confirm JDK vendor
- Confirm Tomcat policy
- Select pilot services
- Record rollback images
- Approve performance gates

### Week 1 — Parent and shared libraries

- Create `boot-parent:2.0.0-RC1`
- Build Boot 4 versions of IDFC starters
- Align security/observability libraries
- Generate effective POM and dependency tree
- Run parent security scan
- Publish RC to the internal repository

### Week 2 — Platform and CI

- Build JDK 25 build image
- Build JDK 25 runtime image
- Update GoCD templates
- Add security scans
- Add evidence archive
- Validate JDK 21 rollback pipeline
- Validate Maven/Gradle wrapper behaviour

### Week 3 — Pilot compilation and functional testing

- Migrate pilot services
- Fix Jakarta imports
- Fix starter dependencies
- Fix Jackson compatibility
- Fix tests
- Fix configuration properties
- Run integration tests
- Run Checkmarx and image scans

### Week 4 — Performance and UAT

- JDK 21/Boot 3 baseline test
- JDK 25/Boot 4 comparison
- 48-hour soak where required
- UAT
- Security approval
- Go/No-Go review

### Week 5 — Production pilot

Rollout:

```text
10% pods for 30 minutes
25% pods for 30 minutes
50% pods for 60 minutes
100% after approval
```

Hold the pilot for two stable business days.

### Week 6 onward — Migration waves

Move services by risk and complexity.

---

## 21. Production rollout gates

### Before deployment

- [ ] Approved parent version
- [ ] Approved JDK 25 image digest
- [ ] Approved Tomcat version
- [ ] Effective POM/dependency graph attached
- [ ] Unit tests passed
- [ ] Integration tests passed
- [ ] Contract tests passed
- [ ] Checkmarx/SCA scan approved
- [ ] Container scan approved
- [ ] Performance test approved
- [ ] Soak approved where required
- [ ] Observability verified
- [ ] Rollback image recorded
- [ ] Rollback command tested
- [ ] Architect approval
- [ ] Security approval
- [ ] Service-owner approval

### During canary

Monitor:

- Availability
- Error rate
- p95/p99
- CPU
- RSS
- Heap
- GC
- Restart count
- Readiness failures
- Kafka lag
- Database connections
- Downstream errors
- TLS errors
- Trace volume
- Log volume

### Rollback triggers

Rollback immediately for:

- SLO breach
- Material error-rate increase
- `NoSuchMethodError`
- `NoClassDefFoundError`
- `IllegalAccessError`
- `UnsupportedClassVersionError`
- OOM
- Repeated pod restart
- Sustained CPU/RSS breach
- Kafka lag growth
- Authentication failure increase
- TLS/mTLS failure
- Missing logs, traces or metrics
- New unapproved security finding

### Rollback action

Deploy the pre-built JDK 21 / Boot 3 image.

Do not rebuild the rollback image during the incident.

---

## 22. Service migration pull-request checklist

### Build

- [ ] Parent changed to `2.0.0-RC1` or approved final version
- [ ] Java release set to 25
- [ ] Maven/Gradle version compatible
- [ ] Wrapper files committed where Gradle is used
- [ ] No preview feature enabled
- [ ] No unexplained JVM module-access flags
- [ ] Build passes from a clean workspace

### Dependencies

- [ ] Spring Boot BOM used
- [ ] Tomcat version approved
- [ ] No mixed Tomcat versions
- [ ] Jakarta dependencies used
- [ ] Jackson migration completed or bridge exception recorded
- [ ] Kafka compatibility verified
- [ ] JaCoCo supports Java 25
- [ ] Byte Buddy/Mockito/ASM support Java 25
- [ ] All agents support Java 25
- [ ] No unapproved direct versions
- [ ] No unapproved snapshots/alpha versions

### Code

- [ ] Deprecated Boot 3 APIs removed
- [ ] Configuration-property migration complete
- [ ] `javax` to `jakarta` changes reviewed
- [ ] JSON behaviour verified
- [ ] Security behaviour verified
- [ ] Retry behaviour verified
- [ ] Test annotations migrated
- [ ] Custom starters use Boot 4 versions

### Evidence

- [ ] Build log
- [ ] Effective POM/dependencies
- [ ] JDK scan results
- [ ] Functional test results
- [ ] Contract results
- [ ] Performance results
- [ ] Checkmarx results
- [ ] Container scan
- [ ] UAT approval
- [ ] Rollback proof

---

## 23. Go/No-Go record

### Service details

| Field | Value |
|---|---|
| Service | |
| Owner | |
| Repository/commit | |
| Migration ticket | |
| Current parent | |
| Target parent | |
| JDK 25 image digest | |
| JDK 21 rollback image digest | |
| Tomcat version | |
| Checkmarx report | |
| Performance report | |
| UAT report | |

### Decision

```text
GO
NO-GO
GO WITH TIME-BOUND EXCEPTION
```

### Approvals

| Role | Name | Decision | Date |
|---|---|---|---|
| Service Owner | | | |
| QA | | | |
| Performance QA | | | |
| Security | | | |
| DevOps | | | |
| Architect | | | |

### Exceptions

| Exception | Owner | Risk | Expiry | Removal ticket |
|---|---|---|---|---|
| | | | | |

---

## 24. Definition of Done

A service is considered migrated only when:

- The approved Boot 4 parent is adopted.
- JDK 25 is used for build and runtime.
- The exact JDK vendor and patch are recorded.
- The Tomcat version is security-approved.
- All dependency overrides are documented.
- Build, unit, integration and contract tests pass.
- Jakarta migration is complete.
- JSON contracts are unchanged or explicitly versioned.
- Kafka message contracts are unchanged.
- OAuth2, JWT and mTLS paths pass.
- Aerospike, Redis, JDBC and S3 paths pass where applicable.
- Logs, metrics and traces are verified.
- Checkmarx/dependency/container scans are approved.
- Performance gates pass.
- Soak passes where required.
- Canary passes.
- Rollback is tested.
- Evidence is attached.
- Architect and Security approve production completion.

---

## 25. Architect deliverables

The architect must produce and maintain:

1. Approved target baseline
2. Architecture Decision Record
3. Parent POM / Gradle baseline
4. Dependency override register
5. Checkmarx finding register
6. Shared-starter compatibility matrix
7. Service inventory and wave plan
8. Test and performance gates
9. Production rollout and rollback policy
10. Exception and expiry process
11. Quarterly JDK/Spring/Tomcat patch process
12. Final migration dashboard

### Architect decision principles

- Framework-supported combinations are preferred.
- Security overrides are allowed but governed.
- A clean startup is not migration evidence.
- BOM alignment is the default.
- Security evidence can justify a narrow override.
- Major version mixing is prohibited unless approved.
- Rollback must be pre-built.
- Changes must be measurable.
- Every exception must expire.
- Platform configuration must be centralised rather than copied independently by teams.

---

## 26. Required migration evidence folder

Each service repository or migration ticket should contain:

```text
migration-evidence/
├── baseline-java21-boot3/
│   ├── java-version.txt
│   ├── effective-pom.xml
│   ├── dependency-tree.txt
│   ├── performance-results.md
│   └── scan-reference.txt
├── target-java25-boot4/
│   ├── java-version.txt
│   ├── effective-pom.xml
│   ├── dependency-tree.txt
│   ├── tomcat-tree.txt
│   ├── jdeps.txt
│   ├── jdeprscan.txt
│   ├── test-results/
│   ├── performance-results.md
│   ├── checkmarx-reference.txt
│   └── container-scan-reference.txt
└── go-no-go.md
```

Do not commit sensitive scan contents, credentials, certificate data or production secrets into Git. Store restricted evidence in the approved internal system and place only its reference in the repository.

---

## 27. Immediate next actions

### Architecture Team

- [ ] Approve JDK 25 + Spring Boot 4.1.0 as the strategic baseline
- [ ] Confirm approved JDK vendor and patch
- [ ] Approve parent version `2.0.0-RC1`
- [ ] Confirm whether the Tomcat RC uses Boot-managed 11.0.22 or an approved later patch
- [ ] Select pilot services
- [ ] Approve performance gates

### Security Team

- [ ] Provide exact Tomcat Checkmarx finding
- [ ] Provide CVE/CWE and affected range
- [ ] Approve the Tomcat patch
- [ ] Reconcile other parent dependency overrides
- [ ] Define scan acceptance rules

### Java Platform Team

- [ ] Create Boot 4 parent branch
- [ ] Build Boot 4 shared starters
- [ ] Remove unapproved dependency overrides
- [ ] Add Maven enforcer and evidence generation
- [ ] Publish RC parent
- [ ] Support pilot migrations

### DevOps

- [ ] Build JDK 25 images
- [ ] Update GoCD templates
- [ ] Preserve JDK 21 rollback images
- [ ] Add artifact evidence collection
- [ ] Add canary controls

### Service Teams

- [ ] Complete inventory
- [ ] Capture JDK 21 baseline
- [ ] Adopt RC parent
- [ ] Fix compilation and tests
- [ ] Run full migration test pack
- [ ] Attach evidence
- [ ] Participate in canary support

---

## 28. Final programme decision

The approved strategic direction is:

```text
JDK 25
+ Spring Boot 4.1.0
+ Spring Framework 7
+ Jakarta EE 11
+ Tomcat 11.0.x
+ Security-governed dependency overrides
+ Controlled pilot and wave rollout
+ Tested JDK 21 rollback
```

A Tomcat override introduced because of an IDFC Checkmarx finding must remain until either:

1. The Boot-managed Tomcat version is proven clean by a rescan, or
2. A later approved Tomcat 11.0.x patch is configured and passes compatibility testing, or
3. Security approves a time-bound exception.

No service may move to production based only on successful startup.

plugins {
    id("idfc.library-conventions")
}

// lms-utilities — the digital-lending SYNC lane's LMS-utilities query capability, as
// a LIBRARY the sync ingress (edges:digital-partner-edge) invokes in-thread. NOT the
// async journey engine: no journeyInstanceId, no Kafka, no engine state. It
// implements shared-sync's SyncInvocable; LmsUtilityPort does real HTTP with
// mandatory timeouts. The requestCode (OFFER_CHECK, …) dispatches FAIL-CLOSED against
// a config allow-list — an unknown code is refused, never silently run. The 200 body
// is the IDFC HOUSE envelope; an empty resource_data on a SUCCESS is a legitimate
// business "no offer" (returned clean, not a 5xx); timeout/5xx = technical error. The
// vendor is WireMock in dev — only its DATA is mocked. Real backend host later =
// config swap.
description = "lms-utilities — digital-lending SYNC LMS utilities query (library; real HTTP; vendor mocked in dev)"

dependencies {
    api(project(":shared:shared-sync"))
    implementation(project(":shared:shared-domain"))
    implementation("org.springframework:spring-context")
    implementation("org.springframework:spring-web")
    implementation("org.springframework.boot:spring-boot")
    implementation("org.springframework.boot:spring-boot-autoconfigure")
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("org.slf4j:slf4j-api")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

tasks.named<Test>("test") { useJUnitPlatform() }

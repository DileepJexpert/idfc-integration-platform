plugins {
    id("idfc.spring-boot-app-conventions")
}

// STUB module (Slice 1). Runnable Spring Boot app exposing /actuator/health.
// Real implementation arrives in a later slice; see docs/SLICE1_PUNCH_LIST.md scope fence.
description = "origination-journey (Slice 1 stub)"

application {
    mainClass = "com.idfcfirstbank.integration.orchestration.originationjourney.OriginationJourneyApplication"
}

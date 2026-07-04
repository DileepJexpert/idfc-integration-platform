plugins {
    id("idfc.spring-boot-app-conventions")
}

// THE JOURNEY REGISTRY (workstream A — the designer↔engine seam, mechanized).
// Stores journeys + versions, enforces the maker-checker lifecycle SERVER-SIDE
// (draft -> pendingApproval -> published | rejected, author != approver -> 403,
// at most one editable draft), validates the §7 config graph on submit, and
// serves published configs to the engine. After this service, "publish" in the
// DAG Designer is what the engine runs — config-not-code becomes physically true.
description = "journey-registry — journey/version store with server-enforced maker-checker"

dependencies {
    // Durable store (the org's only datastore); in-memory impl for Docker-free dev/tests.
    implementation("com.aerospike:aerospike-client-jdk21:${property("aerospikeClientVersion")}")
    implementation("com.fasterxml.jackson.core:jackson-databind")
}

// LOCAL-DEV SEED (single source of truth): bundle the engine's CANONICAL
// *.journey.json contract artifacts into this jar under seed-journeys/ so the
// local-profile RegistrySeeder can publish them into an empty registry. No
// committed duplicate — the engine copy (itself locked to the Designer's emitted
// output by JourneyContractLoaderTest) is copied at build time, so it can never
// drift. Production journeys are authored in the Designer; this seeds dev only.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.file("orchestration/origination-journey/src/main/resources/journeys")) {
        include("*.journey.json")
        into("seed-journeys")
    }
}

tasks.named<Test>("test") { useJUnitPlatform { excludeTags("integration") } }

plugins {
    id("idfc.library-conventions")
}

// shared-sync — the digital-lending SYNC lane contracts. The caller BLOCKS for the
// result on the same HTTP call, so this path is deliberately SEPARATE from the async
// journey engine: no journeyInstanceId, no Kafka, no engine state. A capability
// implements SyncInvocable; the SyncCapabilityInvoker dispatches to it in-thread by
// capabilityKey (never by partner/source). The HouseEnvelopeMapper normalizes the
// shared { metadata, resource_data[] } house response (LMS, Karza, future services).
// Plain POJOs — the edge app wires the invoker + a BearerTokenValidator impl.
description = "shared-sync — digital-lending sync-lane contracts (in-thread invoke, house envelope)"

dependencies {
    api(project(":shared:shared-domain"))   // ErrorClass on the sync technical exception
}

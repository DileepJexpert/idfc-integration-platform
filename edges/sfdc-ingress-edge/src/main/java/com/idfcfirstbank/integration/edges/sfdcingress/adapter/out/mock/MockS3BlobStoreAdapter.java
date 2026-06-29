package com.idfcfirstbank.integration.edges.sfdcingress.adapter.out.mock;

import com.idfcfirstbank.integration.edges.sfdcingress.domain.port.BlobStorePort;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mock S3 claim-check (Slice 1 — zero real external URLs). Stores the raw payload
 * in-memory and hands back a {@code s3://} reference; parity (§F) resolves the
 * ref and compares bytes. A real S3 adapter swaps in behind {@link BlobStorePort}
 * with no domain change.
 */
@Component
public class MockS3BlobStoreAdapter implements BlobStorePort {

    private final ConcurrentHashMap<String, byte[]> blobs = new ConcurrentHashMap<>();

    @Override
    public String put(byte[] payload, String contentType) {
        String ref = "s3://idfc-claimcheck/" + UUID.randomUUID();
        blobs.put(ref, payload == null ? new byte[0] : payload.clone());
        return ref;
    }

    @Override
    public byte[] get(String ref) {
        byte[] payload = blobs.get(ref);
        if (payload == null) {
            throw new IllegalArgumentException("no blob for ref " + ref);
        }
        return payload.clone();
    }
}

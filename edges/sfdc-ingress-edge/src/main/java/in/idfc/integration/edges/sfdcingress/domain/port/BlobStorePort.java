package in.idfc.integration.edges.sfdcingress.domain.port;

/**
 * OUT port for the S3 claim-check. The edge stores the raw payload and carries
 * only a reference ({@code s3Ref}) in the canonical envelope. Parity (§F) is
 * defined on the RESOLVED payload — callers fetch the ref and compare bytes.
 */
public interface BlobStorePort {
    String put(byte[] payload, String contentType);

    byte[] get(String ref);
}

package in.idfc.integration.edges.sfdcingress.domain.port;

/**
 * OUT port standing in for the FinnOne stored proc in the Slice 1 backpressure
 * harness (punch list §G). The mock enforces a hard concurrency cap N and
 * records the maximum observed concurrency so the 10x-burst test can assert
 * FinnOne concurrency never exceeds N. This is a METERING harness only — NOT a
 * real FinnOne integration (that is Slice 4).
 */
public interface FinnOneMeterPort {
    void invokeStoredProc(String applicationRef);

    int maxObservedConcurrency();

    long totalInvocations();
}

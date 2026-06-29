package in.idfc.integration.edges.sfdcingress.parity;

import java.util.List;

/**
 * Outcome of a parity check. {@code parity=true} means every parity-relevant
 * field matched within the §F allowlist; any entry in {@code diffs} is an
 * out-of-allowlist difference that blocks cutover.
 */
public record ParityResult(boolean parity, List<String> diffs) {
    public static ParityResult of(List<String> diffs) {
        return new ParityResult(diffs.isEmpty(), List.copyOf(diffs));
    }
}

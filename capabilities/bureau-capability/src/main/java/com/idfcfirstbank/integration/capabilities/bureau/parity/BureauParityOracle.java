package com.idfcfirstbank.integration.capabilities.bureau.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compares the new Bureau capability's output against a recorded fixture captured
 * from the OLD duplicated calls. Parity = same status + same per-bureau normalized
 * fields (score, segment, tradeline counts, amounts). Allowlisted fields
 * (timestamps, raw-blob handle/ordering, vendor source label) are absent from
 * {@link BureauParitySnapshot} by construction, so any diff reported here is a
 * real parity bug that blocks cutover. This is the capability correctness gate.
 */
public class BureauParityOracle {

    public List<String> diff(BureauParitySnapshot expected, BureauParitySnapshot actual) {
        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(expected.status(), actual.status())) {
            diffs.add("status: expected=" + expected.status() + " actual=" + actual.status());
        }
        Set<String> types = new TreeSet<>();
        types.addAll(expected.byType().keySet());
        types.addAll(actual.byType().keySet());
        for (String type : types) {
            var e = expected.byType().get(type);
            var a = actual.byType().get(type);
            if (e == null) {
                diffs.add(type + ": present in actual but not expected");
            } else if (a == null) {
                diffs.add(type + ": expected but missing from actual");
            } else if (!e.equals(a)) {
                diffs.add(type + ": expected=" + e + " actual=" + a);
            }
        }
        return diffs;
    }

    public boolean isParity(BureauParitySnapshot expected, BureauParitySnapshot actual) {
        return diff(expected, actual).isEmpty();
    }
}

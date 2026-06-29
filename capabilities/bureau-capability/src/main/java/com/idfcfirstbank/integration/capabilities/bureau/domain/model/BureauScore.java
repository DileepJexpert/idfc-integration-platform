package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

/**
 * A normalized bureau score. {@code model} names the scoring model (e.g.
 * CIBIL_V3, COMMERCIAL_RANK) so callers can tell scores apart without knowing
 * vendor specifics. Range is carried so a raw value is interpretable.
 */
public record BureauScore(int value, String model, Integer min, Integer max) {
}

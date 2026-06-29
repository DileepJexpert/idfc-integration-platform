package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

/**
 * Canonical credit bureau report fetched from the vendor (CIBIL). Bureau is an
 * INTEGRATION — it fetches the report/score, it does not own or decide on it
 * (the credit decision is the scoring capability's job).
 */
public record BureauReport(int score, String grade, String reportId) {
}

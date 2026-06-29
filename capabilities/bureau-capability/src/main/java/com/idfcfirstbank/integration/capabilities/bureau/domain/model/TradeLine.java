package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One credit account on a bureau report, normalized across vendors. This is the
 * canonical superset of tradeline fields; the exact per-vendor field mapping is
 * confirmed at harvest (step 3) — fields a vendor does not supply stay null.
 */
public record TradeLine(
        String accountType,
        String lender,
        BigDecimal sanctionedAmount,
        BigDecimal currentBalance,
        BigDecimal overdueAmount,
        String status,
        LocalDate openedOn,
        String paymentHistory) {
}

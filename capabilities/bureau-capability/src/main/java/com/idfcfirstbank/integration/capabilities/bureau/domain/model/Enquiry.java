package com.idfcfirstbank.integration.capabilities.bureau.domain.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/** A credit enquiry on a bureau report, normalized across vendors. */
public record Enquiry(LocalDate enquiryDate, String purpose, String lender, BigDecimal amount) {
}

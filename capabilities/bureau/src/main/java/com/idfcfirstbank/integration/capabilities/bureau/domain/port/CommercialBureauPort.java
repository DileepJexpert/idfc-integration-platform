package com.idfcfirstbank.integration.capabilities.bureau.domain.port;

/**
 * OUT port to the CommercialBureau vendor/system (one port per bureau — see
 * {@link BureauVendorPort}). The mock adapter fetches locally; the real adapter
 * hits the vendor over the wire (URL via config).
 */
public interface CommercialBureauPort extends BureauVendorPort {
}

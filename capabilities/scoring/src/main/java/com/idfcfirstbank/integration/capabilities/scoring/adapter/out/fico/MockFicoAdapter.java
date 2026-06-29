package com.idfcfirstbank.integration.capabilities.scoring.adapter.out.fico;

import com.idfcfirstbank.integration.capabilities.scoring.domain.port.FicoPort;

import java.util.Map;

/**
 * Local mock FICO — deterministic demo stub used for unit tests and when
 * {@code idfc.scoring.fico-mode=mock} (no docker vendor needed). Returns a fixed
 * demo score; the payload is accepted but not consulted to keep it deterministic.
 */
public class MockFicoAdapter implements FicoPort {

    @Override
    public int score(Map<String, Object> payload) {
        return 750;
    }
}

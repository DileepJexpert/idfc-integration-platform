package com.idfcfirstbank.integration.capabilities.verification.application;

import com.idfcfirstbank.integration.capabilities.verification.domain.error.VerificationException;
import com.idfcfirstbank.integration.capabilities.verification.domain.port.out.VerificationAdapter;
import com.idfcfirstbank.integration.shared.domain.capability.ErrorClass;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** svcName -> {@link VerificationAdapter}, built from all adapter beans on the classpath. */
@Component
public class AdapterRegistry {

    private final Map<String, VerificationAdapter> bySvcName;

    public AdapterRegistry(List<VerificationAdapter> adapters) {
        this.bySvcName = adapters.stream().collect(Collectors.toMap(VerificationAdapter::svcName, Function.identity()));
    }

    public VerificationAdapter forSvcName(String svcName) {
        VerificationAdapter adapter = bySvcName.get(svcName);
        if (adapter == null) {
            throw new VerificationException(ErrorClass.PERMANENT, "NO_ADAPTER",
                    "no verification adapter registered for svcName=" + svcName);
        }
        return adapter;
    }
}

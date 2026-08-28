package com.huawei.skillcenter.quality;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Compares registered adapter metadata with the reserved external contract inventory.
 * This is deliberately local and read-only: a match is not an execution readiness signal.
 */
@Service
public class ProviderContractVerificationService {
    private final ProviderRegistry providerRegistry;

    public ProviderContractVerificationService(ProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    public List<ProviderContractVerification> verify() {
        Map<String, ProviderDescriptor> active = providerRegistry.list().stream()
                .collect(Collectors.toMap(ProviderDescriptor::id, Function.identity(), (left, right) -> left));
        return ExternalProviderCatalog.list().stream()
                .map(contract -> verify(contract, active.get(contract.id())))
                .toList();
    }

    private ProviderContractVerification verify(ExternalProviderDescriptor contract, ProviderDescriptor actual) {
        if (actual == null) {
            return new ProviderContractVerification(contract.id(), contract.kind(), contract.version(), "",
                    "NOT_REGISTERED", "NOT_REGISTERED", contract.capabilities(), List.of(),
                    contract.capabilities(), List.of(), "EXTERNAL_PROVIDER_NOT_REGISTERED");
        }

        List<String> expected = stable(contract.capabilities());
        List<String> actualCapabilities = stable(actual.capabilities());
        List<String> missing = difference(expected, actualCapabilities);
        List<String> unexpected = difference(actualCapabilities, expected);
        boolean versionMatches = contract.version().equals(actual.version());
        boolean capabilitiesMatch = missing.isEmpty() && unexpected.isEmpty();
        if (!versionMatches || !capabilitiesMatch) {
            return new ProviderContractVerification(contract.id(), contract.kind(), contract.version(), actual.version(),
                    actual.status(), "MISMATCH", expected, actualCapabilities, missing, unexpected,
                    "PROVIDER_CONTRACT_MISMATCH");
        }
        String reason = "UP".equals(actual.status()) ? "PROVIDER_CONTRACT_MATCHED"
                : "CONTRACT_ONLY".equals(actual.status()) ? "PROVIDER_CONTRACT_MATCHED_NOT_ENABLED"
                : "PROVIDER_CONTRACT_MATCHED_NOT_READY";
        return new ProviderContractVerification(contract.id(), contract.kind(), contract.version(), actual.version(),
                actual.status(), "MATCHED", expected, actualCapabilities, List.of(), List.of(), reason);
    }

    private List<String> difference(List<String> left, List<String> right) {
        Set<String> rightSet = Set.copyOf(right);
        return left.stream().filter(value -> !rightSet.contains(value)).toList();
    }

    private List<String> stable(List<String> values) {
        return (values == null ? List.<String>of() : values).stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .toList();
    }
}

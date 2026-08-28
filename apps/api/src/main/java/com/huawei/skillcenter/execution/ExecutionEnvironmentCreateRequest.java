package com.huawei.skillcenter.execution;

import java.util.List;

public record ExecutionEnvironmentCreateRequest(
        String environmentId,
        String kind,
        String version,
        List<String> capabilities,
        String adapterProviderId,
        String configReference
) {
}

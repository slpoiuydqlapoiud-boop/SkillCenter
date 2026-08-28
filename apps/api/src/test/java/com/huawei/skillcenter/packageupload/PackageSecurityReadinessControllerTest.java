package com.huawei.skillcenter.packageupload;

import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.api.RequestIdFilter;
import com.huawei.skillcenter.governance.Actor;
import com.huawei.skillcenter.governance.ActorResolver;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PackageSecurityReadinessControllerTest {
    private MockMvc mockMvc;
    private ActorResolver actorResolver;

    @BeforeEach
    void setUp() {
        actorResolver = mock(ActorResolver.class);
        when(actorResolver.resolve(any())).thenReturn(new Actor("admin-user", "admin"));
        PackageSecurityScanCoordinator coordinator = new PackageSecurityScanCoordinator(
                localPass(), new ContractOnlyExternalPackageSecurityScanner(), PackageSecurityExternalMode.REQUIRED);
        mockMvc = MockMvcBuilders.standaloneSetup(new PackageSecurityReadinessController(coordinator, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();
    }

    @Test
    void readinessExposesSafeContractOnlyMetadata() throws Exception {
        mockMvc.perform(get("/api/v1/admin/package-security/readiness")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-security-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("REQUIRED"))
                .andExpect(jsonPath("$.data.status").value("CONTRACT_ONLY"))
                .andExpect(jsonPath("$.data.scannerId").value("external-package-security"))
                .andExpect(jsonPath("$.data.reasonCode").value("EXTERNAL_SECURITY_SCANNER_CONTRACT_ONLY"))
                .andExpect(jsonPath("$.data.endpoint").doesNotExist())
                .andExpect(jsonPath("$.data.credential").doesNotExist())
                .andExpect(content().string(not(containsString("responseBody"))));
    }

    @Test
    void nonAdminCannotReadReadiness() throws Exception {
        when(actorResolver.resolve(any())).thenReturn(new Actor("viewer-user", "viewer"));

        mockMvc.perform(get("/api/v1/admin/package-security/readiness")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-security-2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));
    }

    @Test
    void readinessShowsMissingRequiredCoverage() throws Exception {
        ExternalPackageSecurityScanner incomplete = new ContractOnlyExternalPackageSecurityScanner() {
            @Override
            public ExternalPackageSecurityScannerHealth health() {
                return new ExternalPackageSecurityScannerHealth("READY", "TEST_READY");
            }

            @Override
            public Set<PackageSecurityScanCapability> capabilities() {
                return Set.of(PackageSecurityScanCapability.MALWARE);
            }
        };
        PackageSecurityScanCoordinator coordinator = new PackageSecurityScanCoordinator(
                localPass(), incomplete, PackageSecurityExternalMode.REQUIRED);
        mockMvc = MockMvcBuilders.standaloneSetup(new PackageSecurityReadinessController(coordinator, actorResolver))
                .setControllerAdvice(new GlobalExceptionHandler(mock(OperationsMetricsService.class)))
                .build();

        mockMvc.perform(get("/api/v1/admin/package-security/readiness")
                        .requestAttr(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-security-3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.reasonCode").value("EXTERNAL_SECURITY_SCANNER_CAPABILITIES_INCOMPLETE"))
                .andExpect(jsonPath("$.data.missingCapabilities").isArray())
                .andExpect(jsonPath("$.data.missingCapabilities", org.hamcrest.Matchers.hasItem("DEPENDENCY_VULNERABILITY")))
                .andExpect(content().string(not(containsString("credential"))));
    }

    private PackageSecurityScanService localPass() {
        return new PackageSecurityScanService() {
            @Override
            public PackageSecurityScanResult scan(java.nio.file.Path zipPath) {
                return new PackageSecurityScanResult("PASSED", List.of());
            }
        };
    }
}

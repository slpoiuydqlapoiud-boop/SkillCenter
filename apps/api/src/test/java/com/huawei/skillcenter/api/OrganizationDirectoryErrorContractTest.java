package com.huawei.skillcenter.api;

import com.huawei.skillcenter.governance.OrganizationDirectoryRevisionConflictException;
import com.huawei.skillcenter.governance.OrganizationDirectoryUnavailableException;
import com.huawei.skillcenter.operations.OperationsMetricsService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class OrganizationDirectoryErrorContractTest {
    @Test
    void unavailableDirectoryUsesSafeServiceUnavailableContract() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(OperationsMetricsService.class));

        ResponseEntity<GlobalExceptionHandler.ErrorEnvelope> response = handler.organizationDirectoryUnavailable(
                new OrganizationDirectoryUnavailableException("DIRECTORY_CREDENTIAL_UNAVAILABLE"), request());

        assertEquals(503, response.getStatusCode().value());
        assertEquals("DIRECTORY_CREDENTIAL_UNAVAILABLE", response.getBody().error().code());
        assertEquals("Organization directory is temporarily unavailable", response.getBody().error().message());
    }

    @Test
    void revisionConflictUsesConflictContract() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler(mock(OperationsMetricsService.class));

        ResponseEntity<GlobalExceptionHandler.ErrorEnvelope> response = handler.organizationDirectoryRevisionConflict(
                new OrganizationDirectoryRevisionConflictException(), request());

        assertEquals(409, response.getStatusCode().value());
        assertEquals("DIRECTORY_REVISION_CONFLICT", response.getBody().error().code());
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE, "request-1");
        return request;
    }
}

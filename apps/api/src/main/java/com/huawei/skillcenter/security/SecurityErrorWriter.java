package com.huawei.skillcenter.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.skillcenter.api.ApiError;
import com.huawei.skillcenter.api.GlobalExceptionHandler;
import com.huawei.skillcenter.api.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public class SecurityErrorWriter {
    private final ObjectMapper objectMapper;

    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request, HttpServletResponse response, int status,
                      String code, String message) throws IOException {
        Object requestId = request.getAttribute(RequestIdFilter.REQUEST_ID_ATTRIBUTE);
        String resolvedRequestId = requestId == null ? "unknown" : requestId.toString();
        GlobalExceptionHandler.ErrorEnvelope envelope = new GlobalExceptionHandler.ErrorEnvelope(
                new ApiError(code, message, List.of()), resolvedRequestId);
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(envelope));
    }
}

package com.cmagent.server.service;

import com.cmagent.core.domain.HttpParameterMapping;
import com.cmagent.core.domain.HttpToolMethod;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public record HttpToolCreateSpec(
        HttpToolMethod method,
        String urlTemplate,
        String inputSchema,
        List<HttpParameterMapping> parameterMappings,
        Map<String, String> secretHeaders,
        Duration timeout
) {
}

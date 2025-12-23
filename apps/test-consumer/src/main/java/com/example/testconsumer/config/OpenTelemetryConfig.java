package com.example.testconsumer.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * OpenTelemetry configuration for Azure Application Insights.
 * 
 * The Azure Monitor OpenTelemetry exporter auto-configures when:
 * - APPLICATIONINSIGHTS_CONNECTION_STRING environment variable is set
 * - azure-monitor-opentelemetry-exporter dependency is on classpath
 * 
 * This class just logs the configuration status.
 */
@Configuration
public class OpenTelemetryConfig {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetryConfig.class);

    @Value("${APPLICATIONINSIGHTS_CONNECTION_STRING:}")
    private String connectionString;

    @PostConstruct
    public void init() {
        if (connectionString != null && !connectionString.isEmpty()) {
            log.info("Azure Application Insights configured - traces will be exported");
        } else {
            log.warn("APPLICATIONINSIGHTS_CONNECTION_STRING not set - traces will not be exported to Azure");
        }
    }
}

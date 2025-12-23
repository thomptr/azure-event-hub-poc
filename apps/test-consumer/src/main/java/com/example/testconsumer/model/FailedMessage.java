package com.example.testconsumer.model;

import java.time.Instant;

public record FailedMessage(
    AccountMessage originalMessage,
    String errorType,
    String errorMessage,
    int httpStatusCode,
    int retryCount,
    String topic,
    int partition,
    long offset,
    Instant timestamp
) {
    public static FailedMessage of(
            AccountMessage message,
            String errorType,
            String errorMessage,
            int httpStatusCode,
            int retryCount,
            String topic,
            int partition,
            long offset) {
        return new FailedMessage(
            message,
            errorType,
            errorMessage,
            httpStatusCode,
            retryCount,
            topic,
            partition,
            offset,
            Instant.now()
        );
    }
}


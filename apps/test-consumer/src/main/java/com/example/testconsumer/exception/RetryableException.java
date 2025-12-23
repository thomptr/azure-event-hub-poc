package com.example.testconsumer.exception;

/**
 * Exception thrown for retryable errors (network issues, timeouts).
 * Messages causing this exception will be retried and eventually sent to DLQ.
 */
public class RetryableException extends RuntimeException {

    public RetryableException(String message) {
        super(message);
    }

    public RetryableException(String message, Throwable cause) {
        super(message, cause);
    }
}


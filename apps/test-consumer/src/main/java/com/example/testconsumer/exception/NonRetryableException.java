package com.example.testconsumer.exception;

/**
 * Exception thrown for non-retryable errors (4xx/5xx responses).
 * Messages causing this exception will be sent to the error topic.
 */
public class NonRetryableException extends RuntimeException {

    private final int httpStatusCode;

    public NonRetryableException(String message, int httpStatusCode) {
        super(message);
        this.httpStatusCode = httpStatusCode;
    }

    public NonRetryableException(String message, int httpStatusCode, Throwable cause) {
        super(message, cause);
        this.httpStatusCode = httpStatusCode;
    }

    public int getHttpStatusCode() {
        return httpStatusCode;
    }
}


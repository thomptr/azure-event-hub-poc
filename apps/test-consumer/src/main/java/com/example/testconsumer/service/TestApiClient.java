package com.example.testconsumer.service;

import com.example.testconsumer.exception.NonRetryableException;
import com.example.testconsumer.exception.RetryableException;
import com.example.testconsumer.model.AccountMessage;
import com.example.testconsumer.model.AccountResponse;
import com.example.testconsumer.model.ApiResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

/**
 * Client for calling the test-api.
 * 
 * Throws RetryableException for network errors and 5xx/429 status codes.
 * Throws NonRetryableException for 4xx client errors.
 * 
 * Retry logic with exponential backoff is handled by the Kafka DefaultErrorHandler.
 */
@Service
public class TestApiClient {

    private static final Logger log = LoggerFactory.getLogger(TestApiClient.class);

    private final WebClient webClient;

    public TestApiClient(WebClient testApiWebClient) {
        this.webClient = testApiWebClient;
    }

    /**
     * Post account message to test-api and return result with HTTP status code.
     * 
     * @throws RetryableException for network errors or retryable HTTP status codes (5xx, 429)
     * @throws NonRetryableException for non-retryable HTTP status codes (4xx)
     */
    public ApiResult postAccount(AccountMessage message) {
        log.info("Calling test-api with message: firstName={}, lastName={}, accountAction={}",
            message.firstName(), message.lastName(), message.accountAction());

        try {
            ResponseEntity<AccountResponse> responseEntity = executeRequest(message);
            int statusCode = responseEntity.getStatusCode().value();
            AccountResponse response = responseEntity.getBody();
            
            log.info("test-api response: status={}, body={}", statusCode, response);
            return ApiResult.success(response, statusCode);

        } catch (WebClientRequestException e) {
            // Network errors (connection refused, timeout, etc.) - retryable
            log.warn("Network error calling test-api: {}", e.getMessage());
            throw new RetryableException("Network error: " + e.getMessage(), e);

        } catch (WebClientResponseException e) {
            // HTTP response errors
            int statusCode = e.getStatusCode().value();
            String responseBody = e.getResponseBodyAsString();
            log.error("test-api returned HTTP {}: {}", statusCode, responseBody);

            if (isRetryableStatusCode(statusCode)) {
                // 5xx server errors and 429 (too many requests) - retryable
                throw new RetryableException(
                    String.format("Retryable HTTP error %d: %s", statusCode, responseBody),
                    e
                );
            } else {
                // 4xx client errors - non-retryable
                throw new NonRetryableException(
                    String.format("Non-retryable HTTP error %d: %s", statusCode, responseBody),
                    statusCode,
                    e
                );
            }
        }
    }

    private ResponseEntity<AccountResponse> executeRequest(AccountMessage message) {
        return webClient.post()
            .uri("/api/accounts")
            .bodyValue(message)
            .retrieve()
            .toEntity(AccountResponse.class)
            .block();
    }

    private boolean isRetryableStatusCode(int statusCode) {
        // 5xx server errors and 429 (too many requests) are retryable
        return statusCode >= 500 || statusCode == 429;
    }
}

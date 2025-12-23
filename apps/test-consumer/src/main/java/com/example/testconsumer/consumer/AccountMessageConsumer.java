package com.example.testconsumer.consumer;

import com.example.testconsumer.entity.AccountTransaction;
import com.example.testconsumer.exception.NonRetryableException;
import com.example.testconsumer.exception.RetryableException;
import com.example.testconsumer.model.AccountMessage;
import com.example.testconsumer.model.ApiResult;
import com.example.testconsumer.service.AccountTransactionService;
import com.example.testconsumer.service.TestApiClient;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.time.Instant;

/**
 * Kafka consumer for AccountMessage.
 * 
 * Error handling:
 * - RetryableException: Retried by Kafka DefaultErrorHandler with exponential backoff.
 *   After max retries, message is sent to DLQ.
 * - NonRetryableException: Not retried, sent directly to DLQ.
 */
@Component
public class AccountMessageConsumer {

    private static final Logger log = LoggerFactory.getLogger(AccountMessageConsumer.class);

    // Azure Event Hub header name for enqueued time
    private static final String EVENT_ENQUEUED_TIME_HEADER = "x-opt-enqueued-time";

    private final TestApiClient testApiClient;
    private final AccountTransactionService transactionService;
    private final Counter messagesConsumedCounter;
    private final Counter messagesSuccessCounter;
    private final Counter messagesErrorCounter;

    public AccountMessageConsumer(
            TestApiClient testApiClient,
            AccountTransactionService transactionService,
            MeterRegistry meterRegistry) {
        this.testApiClient = testApiClient;
        this.transactionService = transactionService;

        this.messagesConsumedCounter = Counter.builder("kafka.messages.consumed")
            .description("Number of Kafka messages consumed")
            .register(meterRegistry);

        this.messagesSuccessCounter = Counter.builder("kafka.messages.success")
            .description("Number of Kafka messages processed successfully")
            .register(meterRegistry);

        this.messagesErrorCounter = Counter.builder("kafka.messages.errors")
            .description("Number of Kafka message processing errors")
            .register(meterRegistry);
    }

    @KafkaListener(
        topics = "${kafka.topic.accounts:accounts}",
        groupId = "${spring.kafka.consumer.group-id}"
    )
    public void consume(ConsumerRecord<String, AccountMessage> record) {
        messagesConsumedCounter.increment();

        AccountMessage message = record.value();
        Instant eventEnqueuedTime = extractEventEnqueuedTime(record);

        log.info("Received message from topic [{}] partition [{}] offset [{}]",
            record.topic(), record.partition(), record.offset());
        log.info("Message key: {}", record.key());
        log.info("Event enqueued time: {}", eventEnqueuedTime);
        log.info("Message payload: firstName={}, lastName={}, accountNumber={}, accountAction={}",
            message.firstName(),
            message.lastName(),
            maskAccountNumber(message.accountNumber()),
            message.accountAction());

        // Save the incoming message to database immediately (status will be null)
        AccountTransaction transaction = transactionService.saveIncomingMessage(
            message,
            record.topic(),
            record.partition(),
            record.offset(),
            eventEnqueuedTime
        );
        log.info("Saved incoming message to database with id: {}", transaction.getId());

        try {
            // Call the test-api with the consumed message
            ApiResult result = testApiClient.postAccount(message);

            // Log the successful response
            log.info("Successfully processed message. API Response: status={}, firstName={}, lastName={}, accountAction={}, message={}",
                result.httpStatusCode(),
                result.response().firstName(),
                result.response().lastName(),
                result.response().accountAction(),
                result.response().message());

            // Update the transaction with the HTTP status code
            transactionService.updateWithStatus(
                transaction.getId(),
                result.httpStatusCode(),
                result.response().message()
            );

            messagesSuccessCounter.increment();

        } catch (NonRetryableException e) {
            // Non-retryable error (4xx client errors) - will be sent to DLQ by error handler
            log.error("Non-retryable error processing message: {}", e.getMessage());
            messagesErrorCounter.increment();

            // Update transaction with error status
            transactionService.updateWithError(
                transaction.getId(),
                e.getHttpStatusCode(),
                e.getMessage()
            );

            // Re-throw to let Kafka error handler send to DLQ
            throw e;

        } catch (RetryableException e) {
            // Retryable error - will be retried by Kafka error handler with exponential backoff
            log.warn("Retryable error processing message (will be retried): {}", e.getMessage());
            messagesErrorCounter.increment();

            // Update transaction with pending retry status
            transactionService.updateWithError(
                transaction.getId(),
                -1,
                "Pending retry: " + e.getMessage()
            );

            // Re-throw to let Kafka error handler retry with exponential backoff
            throw e;

        } catch (Exception e) {
            // Unexpected error - treat as non-retryable
            log.error("Unexpected error processing message: {}", e.getMessage(), e);
            messagesErrorCounter.increment();

            // Update transaction with error status
            transactionService.updateWithError(
                transaction.getId(),
                -2,
                "Unexpected: " + e.getMessage()
            );

            // Wrap in NonRetryableException so it goes directly to DLQ
            throw new NonRetryableException("Unexpected error: " + e.getMessage(), 0, e);
        }
    }

    /**
     * Extract the EventEnqueuedUtcTime from Azure Event Hub headers.
     * Azure Event Hub adds this header when messages are enqueued.
     */
    private Instant extractEventEnqueuedTime(ConsumerRecord<String, AccountMessage> record) {
        // Log all headers for debugging
        if (log.isDebugEnabled()) {
            log.debug("Message headers:");
            for (Header h : record.headers()) {
                log.debug("  Header: {} = {} (length: {})", h.key(), 
                    h.value() != null ? new String(h.value()) : "null",
                    h.value() != null ? h.value().length : 0);
            }
        }

        // Try multiple possible header names for Event Hub enqueued time
        String[] possibleHeaders = {
            EVENT_ENQUEUED_TIME_HEADER,  // x-opt-enqueued-time
            "enqueuedTime",
            "x-opt-enqueued-time-utc"
        };

        for (String headerName : possibleHeaders) {
            Header header = record.headers().lastHeader(headerName);
            if (header != null && header.value() != null) {
                try {
                    byte[] value = header.value();
                    
                    // Try as 8-byte long (milliseconds since epoch)
                    if (value.length == 8) {
                        long epochMillis = ByteBuffer.wrap(value).getLong();
                        log.debug("Parsed {} as long: {}", headerName, epochMillis);
                        return Instant.ofEpochMilli(epochMillis);
                    }
                    
                    // Try as string (ISO-8601 or epoch millis as string)
                    String strValue = new String(value).trim();
                    log.debug("Trying to parse {} as string: '{}'", headerName, strValue);
                    
                    // Try parsing as epoch millis string
                    if (strValue.matches("\\d+")) {
                        return Instant.ofEpochMilli(Long.parseLong(strValue));
                    }
                    
                    // Try parsing as ISO-8601
                    return Instant.parse(strValue);
                    
                } catch (Exception e) {
                    log.warn("Failed to parse {} header: {}", headerName, e.getMessage());
                }
            }
        }
        
        // Return null if header not present (local Kafka without Event Hub)
        log.debug("No enqueued time header found in message");
        return null;
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}

package com.example.testconsumer.service;

import com.example.testconsumer.entity.AccountTransaction;
import com.example.testconsumer.model.AccountMessage;
import com.example.testconsumer.repository.AccountTransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
public class AccountTransactionService {

    private static final Logger log = LoggerFactory.getLogger(AccountTransactionService.class);

    private final AccountTransactionRepository repository;
    private final Counter dbSaveSuccessCounter;
    private final Counter dbSaveErrorCounter;
    private final Counter dbUpdateSuccessCounter;
    private final Counter dbUpdateErrorCounter;

    public AccountTransactionService(AccountTransactionRepository repository, MeterRegistry meterRegistry) {
        this.repository = repository;

        this.dbSaveSuccessCounter = Counter.builder("database.save.success")
            .description("Number of successful database saves")
            .register(meterRegistry);

        this.dbSaveErrorCounter = Counter.builder("database.save.errors")
            .description("Number of database save errors")
            .register(meterRegistry);

        this.dbUpdateSuccessCounter = Counter.builder("database.update.success")
            .description("Number of successful database updates")
            .register(meterRegistry);

        this.dbUpdateErrorCounter = Counter.builder("database.update.errors")
            .description("Number of database update errors")
            .register(meterRegistry);
    }

    /**
     * Save the incoming message immediately when received from Kafka.
     * Status will be null initially.
     */
    @Transactional
    public AccountTransaction saveIncomingMessage(AccountMessage message, String topic, int partition, 
                                                   long offset, Instant eventEnqueuedTime) {
        try {
            AccountTransaction transaction = new AccountTransaction();
            transaction.setFirstName(message.firstName());
            transaction.setLastName(message.lastName());
            transaction.setAccountNumber(message.accountNumber());
            transaction.setAccountAction(message.accountAction());
            transaction.setKafkaTopic(topic);
            transaction.setKafkaPartition(partition);
            transaction.setKafkaOffset(offset);
            transaction.setEventEnqueuedTime(eventEnqueuedTime);
            transaction.setProducerTs(message.producerTs());
            transaction.setReceivedAt(Instant.now());
            // status is null initially

            AccountTransaction saved = repository.save(transaction);

            log.info("Saved incoming message to database: id={}, accountNumber={}, action={}, offset={}",
                saved.getId(),
                maskAccountNumber(saved.getAccountNumber()),
                saved.getAccountAction(),
                offset);

            dbSaveSuccessCounter.increment();
            return saved;

        } catch (Exception e) {
            log.error("Failed to save incoming message to database: {}", e.getMessage(), e);
            dbSaveErrorCounter.increment();
            throw e;
        }
    }

    /**
     * Update the transaction with the HTTP status code from the API response.
     */
    @Transactional
    public AccountTransaction updateWithStatus(Long transactionId, int httpStatusCode, String responseMessage) {
        try {
            AccountTransaction transaction = repository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

            transaction.setStatus(httpStatusCode);
            transaction.setResponseMessage(responseMessage);
            transaction.setProcessedAt(Instant.now());

            AccountTransaction updated = repository.save(transaction);

            log.info("Updated transaction with status: id={}, status={}, processedAt={}",
                updated.getId(),
                updated.getStatus(),
                updated.getProcessedAt());

            dbUpdateSuccessCounter.increment();
            return updated;

        } catch (Exception e) {
            log.error("Failed to update transaction status: id={}, error={}", transactionId, e.getMessage(), e);
            dbUpdateErrorCounter.increment();
            throw e;
        }
    }

    /**
     * Update the transaction with error status (for failed API calls).
     */
    @Transactional
    public AccountTransaction updateWithError(Long transactionId, int errorCode, String errorMessage) {
        try {
            AccountTransaction transaction = repository.findById(transactionId)
                .orElseThrow(() -> new IllegalArgumentException("Transaction not found: " + transactionId));

            transaction.setStatus(errorCode);
            transaction.setResponseMessage(errorMessage);
            transaction.setProcessedAt(Instant.now());

            AccountTransaction updated = repository.save(transaction);

            log.warn("Updated transaction with error: id={}, status={}, error={}",
                updated.getId(),
                updated.getStatus(),
                errorMessage);

            dbUpdateSuccessCounter.increment();
            return updated;

        } catch (Exception e) {
            log.error("Failed to update transaction with error: id={}, error={}", transactionId, e.getMessage(), e);
            dbUpdateErrorCounter.increment();
            throw e;
        }
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}

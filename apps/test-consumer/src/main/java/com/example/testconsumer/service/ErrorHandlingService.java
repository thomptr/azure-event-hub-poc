package com.example.testconsumer.service;

import com.example.testconsumer.model.AccountMessage;
import com.example.testconsumer.model.FailedMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class ErrorHandlingService {

    private static final Logger log = LoggerFactory.getLogger(ErrorHandlingService.class);

    private final KafkaTemplate<String, FailedMessage> kafkaTemplate;
    private final String dlqTopic;
    private final String errorTopic;
    private final Counter dlqCounter;
    private final Counter errorTopicCounter;

    public ErrorHandlingService(
            KafkaTemplate<String, FailedMessage> kafkaTemplate,
            @Value("${kafka.topic.dlq:accounts-dlq}") String dlqTopic,
            @Value("${kafka.topic.error:accounts-error}") String errorTopic,
            MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.dlqTopic = dlqTopic;
        this.errorTopic = errorTopic;

        this.dlqCounter = Counter.builder("kafka.messages.dlq")
            .description("Number of messages sent to DLQ")
            .register(meterRegistry);

        this.errorTopicCounter = Counter.builder("kafka.messages.error")
            .description("Number of messages sent to error topic")
            .register(meterRegistry);
    }

    /**
     * Send message to Dead Letter Queue (for retryable errors after exhausting retries)
     */
    public void sendToDlq(AccountMessage message, String errorMessage, int retryCount,
                          String topic, int partition, long offset) {
        FailedMessage failedMessage = FailedMessage.of(
            message,
            "RETRY_EXHAUSTED",
            errorMessage,
            0,
            retryCount,
            topic,
            partition,
            offset
        );

        log.warn("Sending message to DLQ [{}]: accountNumber={}, error={}",
            dlqTopic, maskAccountNumber(message.accountNumber()), errorMessage);

        kafkaTemplate.send(dlqTopic, message.accountNumber(), failedMessage)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to send message to DLQ: {}", ex.getMessage(), ex);
                } else {
                    log.info("Message sent to DLQ: topic={}, partition={}, offset={}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                    dlqCounter.increment();
                }
            });
    }

    /**
     * Send message to Error Topic (for non-retryable errors like 4xx/5xx)
     */
    public void sendToErrorTopic(AccountMessage message, String errorMessage, int httpStatusCode,
                                  String topic, int partition, long offset) {
        FailedMessage failedMessage = FailedMessage.of(
            message,
            "NON_RETRYABLE",
            errorMessage,
            httpStatusCode,
            0,
            topic,
            partition,
            offset
        );

        log.error("Sending message to error topic [{}]: accountNumber={}, httpStatus={}, error={}",
            errorTopic, maskAccountNumber(message.accountNumber()), httpStatusCode, errorMessage);

        kafkaTemplate.send(errorTopic, message.accountNumber(), failedMessage)
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to send message to error topic: {}", ex.getMessage(), ex);
                } else {
                    log.info("Message sent to error topic: topic={}, partition={}, offset={}",
                        result.getRecordMetadata().topic(),
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
                    errorTopicCounter.increment();
                }
            });
    }

    private String maskAccountNumber(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return "****";
        }
        return "****" + accountNumber.substring(accountNumber.length() - 4);
    }
}


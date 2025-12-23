package com.example.testconsumer.config;

import com.example.testconsumer.exception.NonRetryableException;
import com.example.testconsumer.exception.RetryableException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaErrorHandlerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlerConfig.class);

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.sasl.mechanism:#{null}}")
    private String saslMechanism;

    @Value("${spring.kafka.properties.security.protocol:#{null}}")
    private String securityProtocol;

    @Value("${spring.kafka.properties.sasl.jaas.config:#{null}}")
    private String saslJaasConfig;

    @Value("${kafka.dlq.topic:accounts-dlq}")
    private String dlqTopic;

    @Value("${kafka.error-handler.max-retries:3}")
    private int maxRetries;

    @Value("${kafka.error-handler.initial-interval-ms:1000}")
    private long initialIntervalMs;

    @Value("${kafka.error-handler.multiplier:2.0}")
    private double multiplier;

    @Value("${kafka.error-handler.max-interval-ms:10000}")
    private long maxIntervalMs;

    /**
     * Producer factory for DLQ publishing
     */
    @Bean
    public ProducerFactory<Object, Object> dlqProducerFactory() {
        Map<String, Object> configProps = new HashMap<>();
        configProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        configProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        configProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);

        // Azure Event Hub security settings
        if (securityProtocol != null) {
            configProps.put("security.protocol", securityProtocol);
        }
        if (saslMechanism != null) {
            configProps.put("sasl.mechanism", saslMechanism);
        }
        if (saslJaasConfig != null) {
            configProps.put("sasl.jaas.config", saslJaasConfig);
        }

        return new DefaultKafkaProducerFactory<>(configProps);
    }

    /**
     * Kafka template for DLQ publishing
     */
    @Bean
    public KafkaTemplate<Object, Object> dlqKafkaTemplate() {
        return new KafkaTemplate<>(dlqProducerFactory());
    }

    /**
     * Dead Letter Publishing Recoverer - sends messages to DLQ after retries exhausted
     */
    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<Object, Object> dlqKafkaTemplate) {
        
        return new DeadLetterPublishingRecoverer(
            (KafkaOperations<Object, Object>) dlqKafkaTemplate,
            (record, exception) -> {
                // Route all failed messages to the DLQ topic
                log.error("Sending message to DLQ [{}] after exhausting retries. Original topic: {}, partition: {}, offset: {}. Error: {}",
                    dlqTopic,
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    exception.getMessage());
                return new TopicPartition(dlqTopic, record.partition() % 2); // Distribute across DLQ partitions
            }
        );
    }

    /**
     * Default Error Handler with:
     * - Blocking retries for RetryableException
     * - Exponential backoff (1s, 2s, 4s, etc.)
     * - Max 3 retries
     * - DLQ recovery after retries exhausted
     */
    @Bean
    public CommonErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        // Configure exponential backoff
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
        backOff.setInitialInterval(initialIntervalMs);
        backOff.setMultiplier(multiplier);
        backOff.setMaxInterval(maxIntervalMs);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);

        // Configure which exceptions are retryable
        // RetryableException - will be retried with backoff
        errorHandler.addRetryableExceptions(RetryableException.class);
        
        // NonRetryableException - will NOT be retried, goes directly to DLQ
        errorHandler.addNotRetryableExceptions(NonRetryableException.class);

        // Log retry attempts
        errorHandler.setRetryListeners((record, ex, deliveryAttempt) -> {
            log.warn("Retry attempt {} of {} for topic [{}] partition [{}] offset [{}]. Error: {}",
                deliveryAttempt,
                maxRetries,
                record.topic(),
                record.partition(),
                record.offset(),
                ex.getMessage());
        });

        log.info("Configured Kafka error handler with {} max retries, initial interval {}ms, multiplier {}, max interval {}ms",
            maxRetries, initialIntervalMs, multiplier, maxIntervalMs);

        return errorHandler;
    }
}


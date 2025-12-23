package com.example.testconsumer.config;

import com.example.testconsumer.model.AccountMessage;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.MicrometerConsumerListener;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@EnableKafka
@Configuration
public class KafkaConsumerConfig {

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.consumer.group-id}")
    private String groupId;

    @Value("${spring.kafka.consumer.auto-offset-reset:earliest}")
    private String autoOffsetReset;

    @Value("${spring.kafka.properties.sasl.mechanism:#{null}}")
    private String saslMechanism;

    @Value("${spring.kafka.properties.security.protocol:#{null}}")
    private String securityProtocol;

    @Value("${spring.kafka.properties.sasl.jaas.config:#{null}}")
    private String saslJaasConfig;

    @Bean
    public ConsumerFactory<String, AccountMessage> consumerFactory(MeterRegistry meterRegistry) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, autoOffsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        
        // Disable auto-commit for manual acknowledgement with error handling
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        // Azure Event Hub / Kafka security settings
        if (securityProtocol != null) {
            props.put("security.protocol", securityProtocol);
        }
        if (saslMechanism != null) {
            props.put("sasl.mechanism", saslMechanism);
        }
        if (saslJaasConfig != null) {
            props.put("sasl.jaas.config", saslJaasConfig);
        }

        JsonDeserializer<AccountMessage> deserializer = new JsonDeserializer<>(AccountMessage.class);
        deserializer.addTrustedPackages("*");
        deserializer.setUseTypeMapperForKey(false);
        // Ignore type headers from producer - use our own AccountMessage class
        deserializer.setUseTypeHeaders(false);

        DefaultKafkaConsumerFactory<String, AccountMessage> factory = 
            new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), deserializer);
        
        // Add Micrometer listener to expose native Kafka consumer metrics
        factory.addListener(new MicrometerConsumerListener<>(meterRegistry));
        
        return factory;
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, AccountMessage> kafkaListenerContainerFactory(
            ConsumerFactory<String, AccountMessage> consumerFactory,
            CommonErrorHandler kafkaErrorHandler) {
        
        ConcurrentKafkaListenerContainerFactory<String, AccountMessage> factory =
            new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(1);
        
        // Set the custom error handler with retry and DLQ support
        factory.setCommonErrorHandler(kafkaErrorHandler);
        
        return factory;
    }
}

package com.example.testproducer.service;

import com.example.testproducer.model.AccountMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MessageProducerService {

    private static final Logger log = LoggerFactory.getLogger(MessageProducerService.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final RandomDataGenerator randomDataGenerator;
    private final MeterRegistry meterRegistry;

    @Value("${producer.topic}")
    private String topic;

    @Value("${producer.messages-per-second:1}")
    private int messagesPerSecond;

    @Value("${producer.auto-start:false}")
    private boolean autoStart;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong messagesSent = new AtomicLong(0);
    private final AtomicLong messagesSuccessful = new AtomicLong(0);
    private final AtomicLong messagesFailed = new AtomicLong(0);

    private ScheduledExecutorService scheduler;
    private Counter sentCounter;
    private Counter successCounter;
    private Counter failureCounter;

    public MessageProducerService(KafkaTemplate<String, Object> kafkaTemplate,
                                   RandomDataGenerator randomDataGenerator,
                                   MeterRegistry meterRegistry) {
        this.kafkaTemplate = kafkaTemplate;
        this.randomDataGenerator = randomDataGenerator;
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    public void init() {
        sentCounter = meterRegistry.counter("producer.messages.sent");
        successCounter = meterRegistry.counter("producer.messages.success");
        failureCounter = meterRegistry.counter("producer.messages.failed");

        if (autoStart) {
            log.info("Auto-starting producer with {} messages/second", messagesPerSecond);
            start();
        }
    }

    public synchronized void start() {
        if (running.get()) {
            log.warn("Producer is already running");
            return;
        }

        running.set(true);
        scheduler = Executors.newScheduledThreadPool(2);

        // Calculate interval in microseconds for precise rate control
        long intervalMicros = 1_000_000L / messagesPerSecond;
        
        log.info("Starting producer: {} messages/second (interval: {}μs), topic: {}", 
                messagesPerSecond, intervalMicros, topic);

        // Use a high-frequency scheduler for precise rate control
        scheduler.scheduleAtFixedRate(this::sendMessage, 0, intervalMicros, TimeUnit.MICROSECONDS);
    }

    public synchronized void stop() {
        if (!running.get()) {
            log.warn("Producer is not running");
            return;
        }

        running.set(false);
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("Producer stopped. Total sent: {}, successful: {}, failed: {}",
                messagesSent.get(), messagesSuccessful.get(), messagesFailed.get());
    }

    private void sendMessage() {
        if (!running.get()) {
            return;
        }

        try {
            AccountMessage message = randomDataGenerator.generateRandomAccountMessage();
            String key = UUID.randomUUID().toString();

            CompletableFuture<SendResult<String, Object>> future = kafkaTemplate.send(topic, key, message);
            messagesSent.incrementAndGet();
            sentCounter.increment();

            future.whenComplete((result, ex) -> {
                if (ex == null) {
                    messagesSuccessful.incrementAndGet();
                    successCounter.increment();
                    if (messagesSuccessful.get() % 1000 == 0) {
                        log.debug("Sent {} messages successfully", messagesSuccessful.get());
                    }
                } else {
                    messagesFailed.incrementAndGet();
                    failureCounter.increment();
                    log.error("Failed to send message: {}", ex.getMessage());
                }
            });
        } catch (Exception e) {
            messagesFailed.incrementAndGet();
            failureCounter.increment();
            log.error("Error producing message: {}", e.getMessage());
        }
    }

    public void setMessagesPerSecond(int rate) {
        boolean wasRunning = running.get();
        if (wasRunning) {
            stop();
        }
        this.messagesPerSecond = rate;
        log.info("Rate changed to {} messages/second", rate);
        if (wasRunning) {
            start();
        }
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getMessagesPerSecond() {
        return messagesPerSecond;
    }

    public ProducerStats getStats() {
        return new ProducerStats(
                running.get(),
                messagesPerSecond,
                messagesSent.get(),
                messagesSuccessful.get(),
                messagesFailed.get(),
                topic
        );
    }

    public void resetStats() {
        messagesSent.set(0);
        messagesSuccessful.set(0);
        messagesFailed.set(0);
        log.info("Stats reset");
    }

    public record ProducerStats(
            boolean running,
            int messagesPerSecond,
            long messagesSent,
            long messagesSuccessful,
            long messagesFailed,
            String topic
    ) {}
}


package com.example.testproducer.controller;

import com.example.testproducer.service.MessageProducerService;
import com.example.testproducer.service.MessageProducerService.ProducerStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/producer")
public class ProducerController {

    private static final Logger log = LoggerFactory.getLogger(ProducerController.class);

    private final MessageProducerService producerService;

    public ProducerController(MessageProducerService producerService) {
        this.producerService = producerService;
    }

    /**
     * Start the message producer
     */
    @PostMapping("/start")
    public ResponseEntity<Map<String, Object>> start() {
        log.info("Received request to start producer");
        producerService.start();
        return ResponseEntity.ok(Map.of(
                "status", "started",
                "messagesPerSecond", producerService.getMessagesPerSecond()
        ));
    }

    /**
     * Stop the message producer
     */
    @PostMapping("/stop")
    public ResponseEntity<Map<String, Object>> stop() {
        log.info("Received request to stop producer");
        producerService.stop();
        ProducerStats stats = producerService.getStats();
        return ResponseEntity.ok(Map.of(
                "status", "stopped",
                "totalSent", stats.messagesSent(),
                "successful", stats.messagesSuccessful(),
                "failed", stats.messagesFailed()
        ));
    }

    /**
     * Get current producer status and statistics
     */
    @GetMapping("/status")
    public ResponseEntity<ProducerStats> status() {
        return ResponseEntity.ok(producerService.getStats());
    }

    /**
     * Update the messages per second rate
     */
    @PostMapping("/rate")
    public ResponseEntity<Map<String, Object>> setRate(@RequestParam int messagesPerSecond) {
        if (messagesPerSecond < 1 || messagesPerSecond > 10000) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Rate must be between 1 and 10000 messages per second"
            ));
        }
        log.info("Updating rate to {} messages/second", messagesPerSecond);
        producerService.setMessagesPerSecond(messagesPerSecond);
        return ResponseEntity.ok(Map.of(
                "status", "rate_updated",
                "messagesPerSecond", messagesPerSecond,
                "running", producerService.isRunning()
        ));
    }

    /**
     * Reset statistics counters
     */
    @PostMapping("/reset-stats")
    public ResponseEntity<Map<String, String>> resetStats() {
        producerService.resetStats();
        return ResponseEntity.ok(Map.of("status", "stats_reset"));
    }
}


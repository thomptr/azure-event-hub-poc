package com.example.testapi.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Random;

@Service
public class RandomDataGenerator {

    private final Random random = new Random();

    private static final List<String> MESSAGES = List.of(
        "Request processed successfully",
        "Account action completed",
        "Operation finished without errors",
        "Transaction acknowledged",
        "Request received and handled",
        "Action completed successfully",
        "Processing complete",
        "Request fulfilled",
        "Operation successful",
        "Task completed"
    );

    public String generateMessage() {
        return MESSAGES.get(random.nextInt(MESSAGES.size()));
    }
}


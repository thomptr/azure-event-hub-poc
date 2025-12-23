package com.example.testproducer.service;

import com.example.testproducer.model.AccountMessage;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;

@Service
public class RandomDataGenerator {

    private final Random random = new Random();

    private static final List<String> FIRST_NAMES = List.of(
            "James", "Mary", "John", "Patricia", "Robert", "Jennifer", "Michael", "Linda",
            "William", "Elizabeth", "David", "Barbara", "Richard", "Susan", "Joseph", "Jessica",
            "Thomas", "Sarah", "Charles", "Karen", "Christopher", "Lisa", "Daniel", "Nancy",
            "Matthew", "Betty", "Anthony", "Margaret", "Mark", "Sandra", "Donald", "Ashley"
    );

    private static final List<String> LAST_NAMES = List.of(
            "Smith", "Johnson", "Williams", "Brown", "Jones", "Garcia", "Miller", "Davis",
            "Rodriguez", "Martinez", "Hernandez", "Lopez", "Gonzalez", "Wilson", "Anderson",
            "Thomas", "Taylor", "Moore", "Jackson", "Martin", "Lee", "Perez", "Thompson",
            "White", "Harris", "Sanchez", "Clark", "Ramirez", "Lewis", "Robinson", "Walker"
    );

    private static final List<String> ACCOUNT_ACTIONS = List.of(
            "CREATE", "UPDATE", "DELETE", "ACTIVATE", "DEACTIVATE", "SUSPEND", "REACTIVATE",
            "VERIFY", "CLOSE", "TRANSFER", "UPGRADE", "DOWNGRADE"
    );

    public AccountMessage generateRandomAccountMessage() {
        return new AccountMessage(
                getRandomFirstName(),
                getRandomLastName(),
                generateAccountNumber(),
                getRandomAccountAction(),
                Instant.now()  // Set producer timestamp
        );
    }

    public String getRandomFirstName() {
        return FIRST_NAMES.get(random.nextInt(FIRST_NAMES.size()));
    }

    public String getRandomLastName() {
        return LAST_NAMES.get(random.nextInt(LAST_NAMES.size()));
    }

    public String generateAccountNumber() {
        // Generate account number format: ACC-XXXXXXXX (8 hex chars)
        return "ACC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    public String getRandomAccountAction() {
        return ACCOUNT_ACTIONS.get(random.nextInt(ACCOUNT_ACTIONS.size()));
    }
}

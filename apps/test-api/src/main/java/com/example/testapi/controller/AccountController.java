package com.example.testapi.controller;

import com.example.testapi.model.AccountRequest;
import com.example.testapi.model.AccountResponse;
import com.example.testapi.service.RandomDataGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private static final Logger log = LoggerFactory.getLogger(AccountController.class);

    private final RandomDataGenerator randomDataGenerator;

    public AccountController(RandomDataGenerator randomDataGenerator) {
        this.randomDataGenerator = randomDataGenerator;
    }

    @PostMapping
    public ResponseEntity<AccountResponse> createAccount(@RequestBody AccountRequest request) {
        log.info("Received request: {}", request);

        // Generate a random response message
        String randomMessage = randomDataGenerator.generateMessage();

        AccountResponse response = new AccountResponse(
            request.firstName(),
            request.lastName(),
            request.accountNumber(),
            request.accountAction(),
            randomMessage
        );

        log.info("Returning response: {}", response);
        return ResponseEntity.ok(response);
    }
}


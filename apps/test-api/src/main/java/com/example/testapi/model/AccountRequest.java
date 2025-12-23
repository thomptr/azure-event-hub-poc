package com.example.testapi.model;

import java.time.Instant;

public record AccountRequest(
    String firstName,
    String lastName,
    String accountNumber,
    String accountAction,
    Instant producerTs
) {}

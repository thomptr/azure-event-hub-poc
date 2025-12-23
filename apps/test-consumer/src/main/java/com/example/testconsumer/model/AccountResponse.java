package com.example.testconsumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountResponse(
    String firstName,
    String lastName,
    String accountNumber,
    String accountAction,
    String message
) {}


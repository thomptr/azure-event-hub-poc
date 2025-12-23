package com.example.testapi.model;

public record AccountResponse(
    String firstName,
    String lastName,
    String accountNumber,
    String accountAction,
    String message
) {}


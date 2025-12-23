package com.example.testconsumer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountMessage(
    String firstName,
    String lastName,
    String accountNumber,
    String accountAction,
    Instant producerTs
) {}

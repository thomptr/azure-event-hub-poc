package com.example.testproducer.model;

import java.time.Instant;

public class AccountMessage {
    private String firstName;
    private String lastName;
    private String accountNumber;
    private String accountAction;
    private Instant producerTs;

    public AccountMessage() {
    }

    public AccountMessage(String firstName, String lastName, String accountNumber, String accountAction, Instant producerTs) {
        this.firstName = firstName;
        this.lastName = lastName;
        this.accountNumber = accountNumber;
        this.accountAction = accountAction;
        this.producerTs = producerTs;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public void setAccountNumber(String accountNumber) {
        this.accountNumber = accountNumber;
    }

    public String getAccountAction() {
        return accountAction;
    }

    public void setAccountAction(String accountAction) {
        this.accountAction = accountAction;
    }

    public Instant getProducerTs() {
        return producerTs;
    }

    public void setProducerTs(Instant producerTs) {
        this.producerTs = producerTs;
    }

    @Override
    public String toString() {
        return "AccountMessage{" +
                "firstName='" + firstName + '\'' +
                ", lastName='" + lastName + '\'' +
                ", accountNumber='" + accountNumber + '\'' +
                ", accountAction='" + accountAction + '\'' +
                ", producerTs=" + producerTs +
                '}';
    }
}

package com.example.testproducer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TestProducerApplication {

    public static void main(String[] args) {
        SpringApplication.run(TestProducerApplication.class, args);
    }
}


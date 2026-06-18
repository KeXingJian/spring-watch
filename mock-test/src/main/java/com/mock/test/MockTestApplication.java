package com.mock.test;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"com.mock.test"})
public class MockTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockTestApplication.class, args);
    }
}

package com.springwatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class SpringWatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(SpringWatchApplication.class, args);
    }
}
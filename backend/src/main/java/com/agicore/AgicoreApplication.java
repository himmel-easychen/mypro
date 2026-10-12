package com.agicore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AgicoreApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgicoreApplication.class, args);
    }
}

package com.movieclash;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class MovieClashApplication {

    public static void main(String[] args) {
        SpringApplication.run(MovieClashApplication.class, args);
    }
}


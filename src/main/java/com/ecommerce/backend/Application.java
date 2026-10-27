package com.ecommerce.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the modular monolith.
 *
 * <p>This class lives in the root package so component scanning covers every module
 * ({@code common}, {@code system}, and later {@code auth}, {@code user}, ...).
 */
@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}

package com.codelens;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class CodeLensApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodeLensApplication.class, args);
    }
}

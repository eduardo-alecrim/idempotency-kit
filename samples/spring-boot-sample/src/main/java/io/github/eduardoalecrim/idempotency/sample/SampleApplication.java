// SPDX-License-Identifier: Apache-2.0
package io.github.eduardoalecrim.idempotency.sample;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Starts the sample on port 8080. */
@SpringBootApplication
public class SampleApplication {

    /** Runs the application. */
    public static void main(String[] args) {
        SpringApplication.run(SampleApplication.class, args);
    }
}

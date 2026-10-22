package com.inn.cafe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * @EnableScheduling powers the config-gated nightly automation sweep
 * (cafe.automation.scheduled-enabled, default FALSE — nothing runs unless an
 * operator explicitly opts in; the sweep only RECORDS detections, never sends).
 */
@SpringBootApplication
@EnableScheduling
public class CafeManagementSystemApplication {

	public static void main(String[] args) {
		SpringApplication.run(CafeManagementSystemApplication.class, args);

	}

}
package com.fazil.payments;

import org.springframework.boot.SpringApplication;

public class TestPaymentIntegrationApplication {

	public static void main(String[] args) {
		SpringApplication.from(PaymentIntegrationApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

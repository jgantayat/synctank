package com.synctank.platform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@ConfigurationPropertiesScan
@SpringBootApplication
public class ContractPlatformApplication {

	public static void main(String[] args) {
		SpringApplication.run(ContractPlatformApplication.class, args);
	}

}

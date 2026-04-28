package com.docsearch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
@ConfigurationPropertiesScan("com.docsearch.config")
public class DocsearchApplication {

	public static void main(String[] args) {
		SpringApplication.run(DocsearchApplication.class, args);
	}
}

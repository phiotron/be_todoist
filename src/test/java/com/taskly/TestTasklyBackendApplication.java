package com.taskly;

import org.springframework.boot.SpringApplication;

public class TestTasklyBackendApplication {

	public static void main(String[] args) {
		SpringApplication.from(TasklyBackendApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}

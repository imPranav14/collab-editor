package com.pranav.collab_editor;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootApplication
public class CollabEditorApplication {

	public static void main(String[] args) {
		SpringApplication.run(CollabEditorApplication.class, args);
	}

	@Bean
	public CommandLineRunner testRedis(StringRedisTemplate redisTemplate) {
		return args -> {
			redisTemplate.opsForValue().set("test", "hello");
			System.out.println("Redis test: " + redisTemplate.opsForValue().get("test"));
		};
	}

}

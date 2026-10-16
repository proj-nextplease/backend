package com.nextplease.backend;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		loadDotenv();
		SpringApplication.run(BackendApplication.class, args);
	}

	private static void loadDotenv() {
		for (Path p : List.of(Paths.get(".env"), Paths.get("BE/.env"), Paths.get("../.env"))) {
			if (Files.isRegularFile(p)) {
				try {
					for (String line : Files.readAllLines(p)) {
						String trimmed = line.trim();
						if (!trimmed.isEmpty() && !trimmed.startsWith("#") && trimmed.contains("=")) {
							int idx = trimmed.indexOf('=');
							String key = trimmed.substring(0, idx).trim();
							String val = trimmed.substring(idx + 1).trim();
							if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
								val = val.substring(1, val.length() - 1);
							}
							if (System.getProperty(key) == null && System.getenv(key) == null) {
								System.setProperty(key, val);
							}
						}
					}
				} catch (IOException ignored) {}
				break;
			}
		}
	}

}


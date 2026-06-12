package com.nextplease.backend.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI nextPleaseOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Next Please Backend API")
                        .version("v1")
                        .description("REST API for Next Please web-first MVP.")
                        .license(new License().name("Private")));
    }
}

package com.ecommerce.backend.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Global OpenAPI metadata. Endpoint details come from annotations on each controller.
 *
 * <p>Swagger UI: {@code /swagger-ui.html}. Raw spec: {@code /v3/api-docs}.
 * Protected operations reference the {@value #BEARER_AUTH} scheme, which adds the
 * "Authorize" button to Swagger UI.
 */
@Configuration(proxyBeanMethods = false)
public class OpenApiConfig {

    public static final String BEARER_AUTH = "bearerAuth";

    @Bean
    public OpenAPI apiDefinition(@Value("${spring.application.name}") String applicationName,
                                 ObjectProvider<BuildProperties> buildProperties) {
        // BuildProperties exists only when the jar was built by Maven (build-info goal).
        String version = buildProperties.stream()
                .map(BuildProperties::getVersion)
                .findFirst()
                .orElse("dev");

        return new OpenAPI()
                .info(new Info()
                        .title(applicationName + " API")
                        .version(version)
                        .description("""
                                Enterprise e-commerce backend.

                                Successful responses use the `ApiResponse` envelope.
                                Errors use RFC 9457 Problem Details (`application/problem+json`)
                                with extra fields `code`, `timestamp`, `requestId` and optional `errors`.

                                Protected endpoints need `Authorization: Bearer <access token>`.
                                """)
                        .license(new License().name("Proprietary")))
                .components(new Components().addSecuritySchemes(BEARER_AUTH, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("Access token from POST /api/v1/auth/login")));
    }
}

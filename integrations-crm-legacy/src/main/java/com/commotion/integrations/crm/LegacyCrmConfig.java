package com.commotion.integrations.crm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class LegacyCrmConfig {

    @Bean
    public RestClient legacyCrmRestClient(
            @Value("${onboarding.crm.base-url}") String baseUrl,
            @Value("${onboarding.crm.api-key:}") String apiKey) {
        RestClient.Builder b = RestClient.builder().baseUrl(baseUrl);
        if (!apiKey.isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + apiKey);
        }
        return b.build();
    }
}

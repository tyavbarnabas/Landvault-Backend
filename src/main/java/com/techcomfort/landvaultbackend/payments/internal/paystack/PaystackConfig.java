package com.techcomfort.landvaultbackend.payments.internal.paystack;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.Builder;

/**
 * The HTTP connection to Paystack. A missing key fails startup — same rule as
 * JWT_SECRET: a deployment that forgets it must not boot and then fail on a
 * buyer's first payment. Logs which mode is active, never the key.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties(PaystackProperties.class)
class PaystackConfig {

    @Bean
    RestClient paystackRestClient(Builder builder, PaystackProperties properties) {
        if (properties.secretKey() == null || properties.secretKey().isBlank()) {
            throw new IllegalStateException("PAYSTACK_SECRET_KEY is not set. Add your Paystack secret key to "
                    + "the environment (.env locally). Refusing to start without it.");
        }
        log.info("Paystack client configured in {} mode", properties.isTestKey() ? "TEST" : "LIVE");

        // Spring Boot's own way to build the HTTP connection: it picks the best
        // HTTP library available and applies the timeouts consistently.
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withConnectTimeout(properties.connectTimeout())
                .withReadTimeout(properties.readTimeout());
        ClientHttpRequestFactory factory = ClientHttpRequestFactoryBuilder.detect().build(settings);
        return builder.clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.secretKey())
                .build();
    }
}

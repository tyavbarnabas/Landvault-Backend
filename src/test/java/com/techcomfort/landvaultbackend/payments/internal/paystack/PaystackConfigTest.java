package com.techcomfort.landvaultbackend.payments.internal.paystack;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The connection to Paystack: no key, no start — and the key never appears in the refusal. */
class PaystackConfigTest {

    private final PaystackConfig config = new PaystackConfig();

    @Test
    void aMissingKeyRefusesToStart() {
        assertThatThrownBy(() -> config.paystackRestClient(RestClient.builder(), properties("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PAYSTACK_SECRET_KEY is not set");
        assertThatThrownBy(() -> config.paystackRestClient(RestClient.builder(), properties(null)))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aKeyBuildsTheClientWithBootsHttpSettings() {
        assertThat(config.paystackRestClient(RestClient.builder(), properties("sk_test_config_test"))).isNotNull();
    }

    @Test
    void theModeIsReadFromTheKeyPrefix() {
        assertThat(properties("sk_test_abc").isTestKey()).isTrue();
        assertThat(properties("sk_live_abc").isTestKey()).isFalse();
    }

    private static PaystackProperties properties(String key) {
        return new PaystackProperties("https://api.paystack.co", key, "http://localhost:8443/payments/return",
                Duration.ofSeconds(5), Duration.ofSeconds(15));
    }
}

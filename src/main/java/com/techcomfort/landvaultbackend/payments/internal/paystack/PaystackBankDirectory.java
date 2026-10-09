package com.techcomfort.landvaultbackend.payments.internal.paystack;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Paystack's bank list, kept in memory for a day (decided with the user):
 * always current, always carrying the codes Paystack needs, and fetched about
 * once a day rather than on every page view. If a refresh fails and an older
 * list exists, the older one is used rather than failing the request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaystackBankDirectory {

    private static final Duration MAX_AGE = Duration.ofDays(1);

    private final PaystackClient paystack;
    private final Clock clock = Clock.systemUTC();

    private volatile Snapshot snapshot;

    private record Snapshot(Instant fetchedAt, List<PaystackClient.Bank> banks,
                            Map<String, PaystackClient.Bank> byCode) {
    }

    public List<PaystackClient.Bank> banks() {
        return current().banks();
    }

    public Optional<PaystackClient.Bank> find(String code) {
        return Optional.ofNullable(current().byCode().get(code));
    }

    private Snapshot current() {
        Snapshot known = snapshot;
        if (known != null && known.fetchedAt().plus(MAX_AGE).isAfter(clock.instant())) {
            return known;
        }
        synchronized (this) {
            known = snapshot;
            if (known != null && known.fetchedAt().plus(MAX_AGE).isAfter(clock.instant())) {
                return known;
            }
            try {
                List<PaystackClient.Bank> banks = paystack.listBanks().stream()
                        .sorted(Comparator.comparing(PaystackClient.Bank::name, String.CASE_INSENSITIVE_ORDER))
                        .toList();
                Map<String, PaystackClient.Bank> byCode = banks.stream()
                        .collect(Collectors.toMap(PaystackClient.Bank::code, Function.identity(), (a, b) -> a));
                snapshot = new Snapshot(clock.instant(), banks, byCode);
                log.info("Paystack bank list refreshed: {} banks", banks.size());
                return snapshot;
            } catch (RuntimeException e) {
                if (known != null) {
                    log.warn("Paystack bank list refresh failed; using the list from {}", known.fetchedAt());
                    return known;
                }
                throw e;
            }
        }
    }

    /** Tests only: forget the cached list. */
    void clear() {
        snapshot = null;
    }
}

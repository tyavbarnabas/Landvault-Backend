package com.techcomfort.landvaultbackend.kyc.internal.security;

import com.techcomfort.landvaultbackend.common.AesGcmCipher;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Encrypts {@code kyc_records.nin_number} at rest (AES-256-GCM via
 * {@link AesGcmCipher}).
 * <p>
 * A converter rather than encrypt/decrypt calls in the service, for the same
 * reason {@code @SQLRestriction} lives on the entity rather than in every
 * repository method: it makes writing plaintext structurally impossible
 * rather than merely discouraged. Nothing in the service layer can forget.
 * <p>
 * An NIN is NDPR-regulated personal data. {@code directors.id_number} carries
 * four standing obligations — encrypt at rest, restrict reads to compliance
 * staff, audit every read, define retention — of which this table was created
 * deliberately not to inherit the first. The other three remain open and are
 * recorded in the column's own Postgres comment, not only here.
 */
@Component
@Converter(autoApply = false)
@RequiredArgsConstructor
@EnableConfigurationProperties(KycProperties.class)
public class KycNinConverter implements AttributeConverter<String, String> {

    private final KycProperties properties;

    private AesGcmCipher cipher;

    @PostConstruct
    void init() {
        this.cipher = AesGcmCipher.fromBase64Key(
                properties.encryptionKey(), "landvault.kyc.encryption-key (KYC_ENCRYPTION_KEY)");
    }

    @Override
    public String convertToDatabaseColumn(String plaintextNin) {
        return cipher.encrypt(plaintextNin);
    }

    @Override
    public String convertToEntityAttribute(String stored) {
        return cipher.decrypt(stored);
    }
}

package com.taskly.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskly.IntegrationTestBase;
import com.taskly.security.SecretCipher;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Bean SecretCipher dựng từ taskly.totp.encryption-key (application-test.yml có khoá giả); issuer = Taskly. */
class TotpWiringTest extends IntegrationTestBase {

    @Autowired
    private SecretCipher secretCipher;

    @Autowired
    private TotpProperties totpProperties;

    @Test
    void secretCipherBeanRoundTrips_andPropertiesHideTheKey() {
        byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

        assertThat(secretCipher.decrypt(secretCipher.encrypt(secret))).isEqualTo(secret);
        assertThat(totpProperties.issuer()).isEqualTo("Taskly");
        assertThat(totpProperties.toString())
                .isEqualTo("TotpProperties[encryptionKey=***, issuer=Taskly]")
                .doesNotContain(totpProperties.encryptionKey());
    }
}

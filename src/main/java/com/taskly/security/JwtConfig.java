package com.taskly.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * JWT HS256 dùng hạ tầng Resource Server có sẵn của Spring Security (thay cho JwtAuthFilter tự viết).
 * Cùng một khoá bí mật để ký (encoder) và xác thực (decoder).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    /** HS256 cần khoá tối thiểu 256 bit. */
    static final int MIN_SECRET_BYTES = 32;

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        String secret = properties.secret();
        if (secret == null || secret.startsWith("${")
                || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            // Không in giá trị secret ra log/lỗi.
            throw new IllegalStateException(
                    "JWT_SECRET chưa được cấu hình hoặc ngắn hơn " + MIN_SECRET_BYTES + " byte (xem .env.example)");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    /** Kiểm chữ ký HS256 + exp (bắt buộc)/nbf theo bean Clock (dung sai 60 giây mặc định) + iss = app.jwt.issuer + sub không rỗng + typ = access + aud chứa taskly-api. */
    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey, JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtTimestampValidator timestampValidator = new JwtTimestampValidator();
        timestampValidator.setClock(clock);
        timestampValidator.setAllowEmptyExpiryClaim(false); // token thiếu exp bị từ chối
        JwtClaimValidator<String> subjectValidator =
                new JwtClaimValidator<>(JwtClaimNames.SUB, sub -> sub != null && !sub.isBlank());
        // Object (không phải String): typ lạ kiểu số / mảng phải bị từ chối (401), không ném ClassCastException (500).
        JwtClaimValidator<Object> typeValidator =
                new JwtClaimValidator<>(JwtTokenIssuer.TYPE_CLAIM, JwtTokenIssuer.ACCESS_TYPE::equals);
        JwtClaimValidator<List<String>> audienceValidator = new JwtClaimValidator<>(JwtClaimNames.AUD,
                aud -> aud != null && aud.contains(JwtTokenIssuer.ACCESS_AUDIENCE));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestampValidator, new JwtIssuerValidator(properties.issuer()), subjectValidator,
                typeValidator, audienceValidator));
        return decoder;
    }
}

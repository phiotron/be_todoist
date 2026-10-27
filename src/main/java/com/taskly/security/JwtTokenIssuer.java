package com.taskly.security;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

/** Phát access JWT: sub = user id (UUID), iss = taskly, aud = taskly-api, typ = access, sống app.jwt.access-ttl (15 phút). */
@Component
public class JwtTokenIssuer {

    /** Claim phân loại token: access / two-factor / google-link (decoder Bearer chỉ nhận access). */
    public static final String TYPE_CLAIM = "typ";
    public static final String ACCESS_TYPE = "access";
    public static final String ACCESS_AUDIENCE = "taskly-api";

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;
    private final Clock clock;

    public JwtTokenIssuer(JwtEncoder jwtEncoder, JwtProperties properties, Clock clock) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
        this.clock = clock;
    }

    public String issueAccessToken(UUID userId) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .audience(List.of(ACCESS_AUDIENCE))
                .claim(TYPE_CLAIM, ACCESS_TYPE)
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(properties.accessTtl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long accessTokenTtlSeconds() {
        return properties.accessTtl().toSeconds();
    }
}

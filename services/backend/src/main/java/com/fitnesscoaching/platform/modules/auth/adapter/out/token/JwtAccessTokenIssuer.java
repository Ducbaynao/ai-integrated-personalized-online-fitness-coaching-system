package com.fitnesscoaching.platform.modules.auth.adapter.out.token;

import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.auth.application.port.out.AccessTokenIssuer;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.auth.domain.IssuedAccessToken;
import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAccessTokenIssuer implements AccessTokenIssuer {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties properties;

    public JwtAccessTokenIssuer(
            JwtEncoder jwtEncoder,
            JwtProperties properties
    ) {
        this.jwtEncoder = jwtEncoder;
        this.properties = properties;
    }

    @Override
    public IssuedAccessToken issue(CurrentUserView user, Instant issuedAt) {
        return issueInternal(user.id(), user.email(), user.roles(), issuedAt);
    }

    @Override
    public IssuedAccessToken issue(UserAccountRecord user, List<String> roles, Instant issuedAt) {
        return issueInternal(user.id(), user.email(), roles, issuedAt);
    }

    private IssuedAccessToken issueInternal(UUID userId, String email, List<String> roles, Instant issuedAt) {
        Instant expiresAt = issuedAt.plus(properties.getAccessTokenTtl());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.getIssuer())
                .subject(userId.toString())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("roles", roles != null ? roles : List.of())
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String value = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedAccessToken(value, expiresAt);
    }
}

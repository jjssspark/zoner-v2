package app.zoner.auth;

import app.zoner.common.api.ApiException;
import app.zoner.common.api.ErrorCode;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class JwtProvider {

    private final JwtProperties props;
    private final Clock clock;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;

    @Autowired
    public JwtProvider(JwtProperties props) {
        this(props, Clock.systemUTC());
    }

    // 테스트에서 과거 시계를 넣어 만료 토큰을 만들기 위한 생성자
    public JwtProvider(JwtProperties props, Clock clock) {
        this.props = props;
        this.clock = clock;
        SecretKey key = new SecretKeySpec(props.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        this.decoder = NimbusJwtDecoder.withSecretKey(key).build();
    }

    public String createAccessToken(UUID userId) {
        Instant now = Instant.now(clock);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(props.accessTtl()))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public UUID parseUserId(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            return UUID.fromString(jwt.getSubject());
        } catch (JwtValidationException e) {
            boolean expired = e.getErrors().stream()
                    .anyMatch(err -> err.getDescription() != null && err.getDescription().contains("expired"));
            throw new ApiException(expired ? ErrorCode.AUTH_TOKEN_EXPIRED : ErrorCode.AUTH_REQUIRED);
        } catch (JwtException | IllegalArgumentException e) {
            throw new ApiException(ErrorCode.AUTH_REQUIRED);
        }
    }
}

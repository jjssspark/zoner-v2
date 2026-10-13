package app.zoner.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("zoner.jwt")
public record JwtProperties(String secret, Duration accessTtl, Duration refreshTtl) {}

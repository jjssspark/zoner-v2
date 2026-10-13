package app.zoner.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("zoner.auth")
public record AuthProperties(boolean cookieSecure) {}

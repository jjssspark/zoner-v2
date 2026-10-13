package app.zoner.common.ratelimit;

import app.zoner.common.api.ApiResponse;
import app.zoner.common.api.ErrorCode;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthRateLimitFilter extends OncePerRequestFilter {

    private static final Set<String> LIMITED_PATHS = Set.of("/api/v1/auth/login", "/api/v1/auth/signup");

    // IP가 늘면 계속 커진다. 설계 기준(가입자 1만)에서는 문제가 안 되고, 거슬리면 Caffeine 캐시로 바꾼다.
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final ObjectMapper mapper;
    private final int limitPerMinute;

    public AuthRateLimitFilter(ObjectMapper mapper,
                               @Value("${zoner.ratelimit.auth-per-minute:10}") int limitPerMinute) {
        this.mapper = mapper;
        this.limitPerMinute = limitPerMinute;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest req) {
        return !("POST".equals(req.getMethod()) && LIMITED_PATHS.contains(req.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Bucket bucket = buckets.computeIfAbsent(clientIp(req), ip -> Bucket.builder()
                .addLimit(Bandwidth.builder().capacity(limitPerMinute)
                        .refillGreedy(limitPerMinute, Duration.ofMinutes(1)).build())
                .build());
        if (bucket.tryConsume(1)) {
            chain.doFilter(req, res);
            return;
        }
        res.setStatus(ErrorCode.RATE_LIMITED.status().value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write(mapper.writeValueAsString(ApiResponse.error(ErrorCode.RATE_LIMITED)));
    }

    // Caddy 뒤에서는 getRemoteAddr()가 항상 Caddy 주소다. 배포 때 server.forward-headers-strategy: framework를
    // 켜고 Caddy가 X-Forwarded-For를 넘기게 해야 진짜 IP가 들어온다. 그때 여기 한 곳만 손본다.
    private String clientIp(HttpServletRequest req) {
        return req.getRemoteAddr();
    }
}

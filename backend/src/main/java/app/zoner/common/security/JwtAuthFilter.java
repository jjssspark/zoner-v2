package app.zoner.common.security;

import app.zoner.auth.JwtProvider;
import app.zoner.common.api.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    public static final String FAILURE_ATTR = "zoner.auth.failure";

    private final JwtProvider jwt;

    public JwtAuthFilter(JwtProvider jwt) { this.jwt = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String header = req.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            try {
                UUID userId = jwt.parseUserId(header.substring(7));
                var auth = new UsernamePasswordAuthenticationToken(userId, null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            } catch (ApiException e) {
                // 여기서 응답을 쓰지 않는다. 어떤 주소가 로그인을 요구하는지는 SecurityConfig가 정하고,
                // 그때 진입점이 이 이유를 읽어 코드를 고른다.
                req.setAttribute(FAILURE_ATTR, e.code());
            }
        }
        chain.doFilter(req, res);
    }
}

package app.zoner;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "zoner.jwt.secret=test-secret-that-is-definitely-longer-than-32-bytes",
        "zoner.auth.cookie-secure=false",
        // 다른 테스트들은 전부 127.0.0.1이라 한 JVM에서 분당 10회를 쉽게 넘긴다. 제한 테스트만 10으로 되돌린다.
        "zoner.ratelimit.auth-per-minute=1000"
})
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired
    protected MockMvc mvc;
}

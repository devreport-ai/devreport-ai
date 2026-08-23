package ai.devreport.backend.usage.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
	"spring.datasource.url=${TEST_POSTGRES_URL}",
	"spring.datasource.username=${TEST_POSTGRES_USERNAME:devreport}",
	"spring.datasource.password=${TEST_POSTGRES_PASSWORD:devreport}",
	"spring.datasource.driver-class-name=org.postgresql.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long",
	"ai.service.mock=true",
	"usage-limits.rate-limit.retention=1m"
})
@EnabledIfEnvironmentVariable(named = "TEST_POSTGRES_URL", matches = ".+")
class RateLimitPostgresqlIntegrationTest {

	@Autowired
	RateLimitService rateLimits;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void storesAndCleansBucketsWithPostgresqlTimestamps() {
		String scope = "postgresql-" + UUID.randomUUID();
		String activeKey = "signup:ip:" + scope;
		String expiredKey = "expired-" + UUID.randomUUID();
		try {
			rateLimits.checkSignup(scope);
			jdbc.update("""
				INSERT INTO rate_limit_buckets (bucket_key, window_started_at, request_count)
				VALUES (?, ?, 1)
				""", expiredKey, OffsetDateTime.now().minusHours(1));

			rateLimits.cleanupExpiredBuckets();

			assertThat(bucketCount(activeKey)).isOne();
			assertThat(bucketCount(expiredKey)).isZero();
		} finally {
			jdbc.update("DELETE FROM rate_limit_buckets WHERE bucket_key IN (?, ?)", activeKey, expiredKey);
		}
	}

	private int bucketCount(String bucketKey) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM rate_limit_buckets WHERE bucket_key = ?",
			Integer.class, bucketKey);
	}
}

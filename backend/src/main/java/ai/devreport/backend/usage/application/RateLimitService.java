package ai.devreport.backend.usage.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import ai.devreport.backend.usage.domain.UsageLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RateLimitService {

	private static final String POSTGRESQL_UPSERT = """
		INSERT INTO rate_limit_buckets (bucket_key, window_started_at, request_count)
		VALUES (?, ?, 1)
		ON CONFLICT (bucket_key) DO UPDATE SET
			window_started_at = EXCLUDED.window_started_at,
			request_count = CASE
				WHEN rate_limit_buckets.window_started_at = EXCLUDED.window_started_at
				THEN rate_limit_buckets.request_count + 1
				ELSE 1
			END
		""";
	private static final String H2_UPSERT = """
		MERGE INTO rate_limit_buckets AS target
		USING (VALUES (?, ?, 1)) AS source(bucket_key, window_started_at, request_count)
		ON target.bucket_key = source.bucket_key
		WHEN MATCHED THEN UPDATE SET
			window_started_at = CASE
				WHEN target.window_started_at = source.window_started_at
				THEN target.window_started_at
				ELSE source.window_started_at
			END,
			request_count = CASE
				WHEN target.window_started_at = source.window_started_at
				THEN target.request_count + 1
				ELSE source.request_count
			END
		WHEN NOT MATCHED THEN INSERT (bucket_key, window_started_at, request_count)
			VALUES (source.bucket_key, source.window_started_at, source.request_count)
		""";

	private final JdbcTemplate jdbc;
	private final UsageLimitProperties properties;
	private final boolean postgresql;

	RateLimitService(JdbcTemplate jdbc, UsageLimitProperties properties) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.postgresql = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
			"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
	}

	@Transactional
	public void checkSignup(String remoteAddress) {
		check("signup", "ip:" + scope(remoteAddress), properties.getRateLimit().getSignup());
	}

	@Transactional
	public void checkLogin(String remoteAddress) {
		check("login", "ip:" + scope(remoteAddress), properties.getRateLimit().getLogin());
	}

	@Transactional
	public void checkRefresh(String remoteAddress) {
		check("refresh", "ip:" + scope(remoteAddress), properties.getRateLimit().getRefresh());
	}

	@Transactional
	public void checkUpload(UUID userId) {
		check("upload", "user:" + userId, properties.getRateLimit().getUpload());
	}

	@Transactional
	public void checkGeneration(UUID userId) {
		check("generation", "user:" + userId, properties.getRateLimit().getGeneration());
	}

	private void check(String endpoint, String scope, int limit) {
		Duration window = properties.getRateLimit().getWindow();
		Instant now = Instant.now();
		long seconds = window.toSeconds();
		long epochSecond = Math.floorDiv(now.getEpochSecond(), seconds) * seconds;
		Instant windowStart = Instant.ofEpochSecond(epochSecond);
		String bucketKey = endpoint + ":" + scope;
		jdbc.update(postgresql ? POSTGRESQL_UPSERT : H2_UPSERT, bucketKey, windowStart);
		Integer count = jdbc.queryForObject(
			"SELECT request_count FROM rate_limit_buckets WHERE bucket_key = ?", Integer.class, bucketKey);
		if (count != null && count > limit) {
			long retryAfter = Math.max(1, Duration.between(now, windowStart.plus(window)).toSeconds() + 1);
			throw new UsageLimitException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED",
				"요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.",
				Map.of("limit", limit, "retryAfterSeconds", retryAfter));
		}
	}

	private static String scope(String value) {
		return value == null || value.isBlank() ? "unknown" : value;
	}
}

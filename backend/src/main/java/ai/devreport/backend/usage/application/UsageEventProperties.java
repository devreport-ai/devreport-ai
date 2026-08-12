package ai.devreport.backend.usage.application;

import java.time.Duration;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "usage-events")
public class UsageEventProperties {

	private static final Duration MAX_RETENTION = Duration.ofDays(90);

	@NotNull
	private Duration retention = MAX_RETENTION;

	@AssertTrue(message = "usage-events.retention must be greater than 0 and no more than 90 days")
	public boolean isRetentionWithinLimit() {
		return retention != null && !retention.isZero() && !retention.isNegative()
			&& retention.compareTo(MAX_RETENTION) <= 0;
	}

	public Duration getRetention() {
		return retention;
	}

	public void setRetention(Duration retention) {
		this.retention = retention;
	}
}

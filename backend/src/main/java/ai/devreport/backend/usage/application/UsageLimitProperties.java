package ai.devreport.backend.usage.application;

import java.time.Duration;
import java.time.ZoneId;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "usage-limits")
public class UsageLimitProperties {

	@NotNull
	private String dayZone = "Asia/Seoul";

	@Valid
	private Upload upload = new Upload();

	@Valid
	private Generation generation = new Generation();

	@Valid
	private Export export = new Export();

	@Valid
	private RateLimit rateLimit = new RateLimit();

	@AssertTrue(message = "usage-limits.day-zone must be a valid time zone")
	public boolean isDayZoneValid() {
		try {
			ZoneId.of(dayZone);
			return true;
		} catch (RuntimeException exception) {
			return false;
		}
	}

	public String getDayZone() {
		return dayZone;
	}

	public void setDayZone(String dayZone) {
		this.dayZone = dayZone;
	}

	public Upload getUpload() {
		return upload;
	}

	public void setUpload(Upload upload) {
		this.upload = upload;
	}

	public Generation getGeneration() {
		return generation;
	}

	public void setGeneration(Generation generation) {
		this.generation = generation;
	}

	public Export getExport() {
		return export;
	}

	public void setExport(Export export) {
		this.export = export;
	}

	public RateLimit getRateLimit() {
		return rateLimit;
	}

	public void setRateLimit(RateLimit rateLimit) {
		this.rateLimit = rateLimit;
	}

	public static class Upload {

		@Min(1)
		private long maxTotalBytes = 500L * 1024 * 1024;

		@Min(1)
		private long maxFiles = 100;

		public long getMaxTotalBytes() {
			return maxTotalBytes;
		}

		public void setMaxTotalBytes(long maxTotalBytes) {
			this.maxTotalBytes = maxTotalBytes;
		}

		public long getMaxFiles() {
			return maxFiles;
		}

		public void setMaxFiles(long maxFiles) {
			this.maxFiles = maxFiles;
		}
	}

	public static class Generation {

		@Min(1)
		private long dailyLimit = 10;

		@Min(1)
		private long concurrentLimit = 2;

		public long getDailyLimit() {
			return dailyLimit;
		}

		public void setDailyLimit(long dailyLimit) {
			this.dailyLimit = dailyLimit;
		}

		public long getConcurrentLimit() {
			return concurrentLimit;
		}

		public void setConcurrentLimit(long concurrentLimit) {
			this.concurrentLimit = concurrentLimit;
		}
	}

	public static class Export {

		@Min(1)
		private long dailyLimit = 20;

		@Min(1)
		private long concurrentLimit = 1;

		public long getDailyLimit() {
			return dailyLimit;
		}

		public void setDailyLimit(long dailyLimit) {
			this.dailyLimit = dailyLimit;
		}

		public long getConcurrentLimit() {
			return concurrentLimit;
		}

		public void setConcurrentLimit(long concurrentLimit) {
			this.concurrentLimit = concurrentLimit;
		}
	}

	public static class RateLimit {

		@NotNull
		private Duration window = Duration.ofMinutes(1);

		@NotNull
		private Duration retention = Duration.ofDays(1);

		@NotBlank
		private String cleanupCron = "0 0 * * * *";

		@NotBlank
		private String cleanupZone = "Asia/Seoul";

		@Min(1)
		private int signup = 10;

		@Min(1)
		private int login = 20;

		@Min(1)
		private int refresh = 30;

		@Min(1)
		private int passwordChange = 5;

		@Min(1)
		private int passwordReset = 5;

		@Min(1)
		private int upload = 30;

		@Min(1)
		private int generation = 10;

		@AssertTrue(message = "usage-limits.rate-limit.window must be greater than 0")
		public boolean isWindowPositive() {
			return window != null && window.toSeconds() > 0;
		}

		@AssertTrue(message = "usage-limits.rate-limit.retention must be at least the rate-limit window")
		public boolean isRetentionValid() {
			return retention != null && !retention.isNegative() && !retention.isZero()
				&& window != null && retention.compareTo(window) >= 0;
		}

		public Duration getWindow() {
			return window;
		}

		public void setWindow(Duration window) {
			this.window = window;
		}

		public Duration getRetention() {
			return retention;
		}

		public void setRetention(Duration retention) {
			this.retention = retention;
		}

		public String getCleanupCron() {
			return cleanupCron;
		}

		public void setCleanupCron(String cleanupCron) {
			this.cleanupCron = cleanupCron;
		}

		public String getCleanupZone() {
			return cleanupZone;
		}

		public void setCleanupZone(String cleanupZone) {
			this.cleanupZone = cleanupZone;
		}

		public int getSignup() {
			return signup;
		}

		public void setSignup(int signup) {
			this.signup = signup;
		}

		public int getLogin() {
			return login;
		}

		public void setLogin(int login) {
			this.login = login;
		}

		public int getRefresh() {
			return refresh;
		}

		public void setRefresh(int refresh) {
			this.refresh = refresh;
		}

		public int getPasswordChange() {
			return passwordChange;
		}

		public void setPasswordChange(int passwordChange) {
			this.passwordChange = passwordChange;
		}

		public int getPasswordReset() {
			return passwordReset;
		}

		public void setPasswordReset(int passwordReset) {
			this.passwordReset = passwordReset;
		}

		public int getUpload() {
			return upload;
		}

		public void setUpload(int upload) {
			this.upload = upload;
		}

		public int getGeneration() {
			return generation;
		}

		public void setGeneration(int generation) {
			this.generation = generation;
		}
	}
}

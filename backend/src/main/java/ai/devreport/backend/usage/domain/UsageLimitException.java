package ai.devreport.backend.usage.domain;

import org.springframework.http.HttpStatus;

public class UsageLimitException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final Object details;

	public UsageLimitException(HttpStatus status, String code, String message, Object details) {
		super(message);
		this.status = status;
		this.code = code;
		this.details = details;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}

	public Object details() {
		return details;
	}
}

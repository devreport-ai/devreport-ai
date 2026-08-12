package ai.devreport.backend.report.domain;

import org.springframework.http.HttpStatus;

public class ReportException extends RuntimeException {

	private final HttpStatus status;
	private final String code;
	private final Object details;

	public ReportException(HttpStatus status, String code, String message) {
		this(status, code, message, null);
	}

	public ReportException(HttpStatus status, String code, String message, Object details) {
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

package ai.devreport.backend.report.domain;

import org.springframework.http.HttpStatus;

public class ReportException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ReportException(HttpStatus status, String code, String message) {
		super(message);
		this.status = status;
		this.code = code;
	}

	public HttpStatus status() {
		return status;
	}

	public String code() {
		return code;
	}
}

package ai.devreport.backend.export.domain;

import org.springframework.http.HttpStatus;

public class ReportExportException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	public ReportExportException(HttpStatus status, String code, String message) {
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

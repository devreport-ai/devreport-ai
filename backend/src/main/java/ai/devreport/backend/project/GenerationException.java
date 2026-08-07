package ai.devreport.backend.project;

import org.springframework.http.HttpStatus;

public class GenerationException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	GenerationException(HttpStatus status, String code, String message) {
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

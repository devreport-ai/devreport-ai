package ai.devreport.backend.project;

import org.springframework.http.HttpStatus;

public class ProjectFileException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	ProjectFileException(HttpStatus status, String code, String message) {
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

package ai.devreport.backend.integration.ai;

import org.springframework.http.HttpStatus;

public class AiServiceException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	AiServiceException(HttpStatus status, String code, String message, Throwable cause) {
		super(message, cause);
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

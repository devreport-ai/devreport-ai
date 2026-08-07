package ai.devreport.backend.auth;

import org.springframework.http.HttpStatus;

public class AuthException extends RuntimeException {

	private final HttpStatus status;
	private final String code;

	AuthException(HttpStatus status, String code, String message) {
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

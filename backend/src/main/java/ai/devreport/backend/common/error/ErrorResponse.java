package ai.devreport.backend.common.error;

import java.time.OffsetDateTime;

public record ErrorResponse(
	String code,
	String message,
	Object details,
	OffsetDateTime timestamp
) {
	public static ErrorResponse of(String code, String message, Object details) {
		return new ErrorResponse(code, message, details, OffsetDateTime.now());
	}
}

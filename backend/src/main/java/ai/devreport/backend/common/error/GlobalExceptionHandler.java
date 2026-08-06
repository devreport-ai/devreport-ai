package ai.devreport.backend.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException exception) {
		return ResponseEntity.badRequest().body(ErrorResponse.of(
			"VALIDATION_FAILED",
			"요청 값이 올바르지 않습니다.",
			validationDetails(exception.getBindingResult())
		));
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<ErrorResponse> handleUnexpected(Exception exception) {
		log.error("Unhandled exception", exception);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ErrorResponse.of(
			"INTERNAL_SERVER_ERROR",
			"서버 오류가 발생했습니다.",
			null
		));
	}

	static Map<String, String> validationDetails(BindingResult bindingResult) {
		Map<String, String> details = new LinkedHashMap<>();
		bindingResult.getFieldErrors().forEach(error ->
			details.putIfAbsent(error.getField(), error.getDefaultMessage()));
		return details;
	}
}

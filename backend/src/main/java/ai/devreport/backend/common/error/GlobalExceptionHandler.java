package ai.devreport.backend.common.error;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

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

	@ExceptionHandler({
		HttpMessageNotReadableException.class,
		MissingServletRequestParameterException.class,
		MissingServletRequestPartException.class,
		MethodArgumentTypeMismatchException.class,
		HandlerMethodValidationException.class
	})
	ResponseEntity<ErrorResponse> handleBadRequest(Exception exception) {
		return ResponseEntity.badRequest().body(ErrorResponse.of(
			"INVALID_REQUEST",
			"요청 형식이 올바르지 않습니다.",
			null
		));
	}

	@ExceptionHandler(ai.devreport.backend.auth.AuthException.class)
	ResponseEntity<ErrorResponse> handleAuth(ai.devreport.backend.auth.AuthException exception) {
		return ResponseEntity.status(exception.status()).body(ErrorResponse.of(
			exception.code(), exception.getMessage(), null));
	}

	@ExceptionHandler(ai.devreport.backend.project.ProjectNotFoundException.class)
	ResponseEntity<ErrorResponse> handleProjectNotFound(
		ai.devreport.backend.project.ProjectNotFoundException exception) {
		return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of(
			"PROJECT_NOT_FOUND", exception.getMessage(), null));
	}

	@ExceptionHandler(ai.devreport.backend.project.ProjectFileException.class)
	ResponseEntity<ErrorResponse> handleProjectFile(
		ai.devreport.backend.project.ProjectFileException exception) {
		if (exception.status().is5xxServerError()) {
			log.error("Project file error: {}", exception.code(), exception);
		}
		return ResponseEntity.status(exception.status()).body(ErrorResponse.of(
			exception.code(), exception.getMessage(), null));
	}

	@ExceptionHandler(ai.devreport.backend.ai.AiServiceException.class)
	ResponseEntity<ErrorResponse> handleAiService(ai.devreport.backend.ai.AiServiceException exception) {
		log.error("AI service request failed", exception);
		return ResponseEntity.status(exception.status()).body(ErrorResponse.of(
			exception.code(), exception.getMessage(), null));
	}

	@ExceptionHandler(MaxUploadSizeExceededException.class)
	ResponseEntity<ErrorResponse> handleFileTooLarge(MaxUploadSizeExceededException exception) {
		return ResponseEntity.status(HttpStatus.CONTENT_TOO_LARGE).body(ErrorResponse.of(
			"FILE_TOO_LARGE", "파일 크기는 20 MiB 이하여야 합니다.", null));
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

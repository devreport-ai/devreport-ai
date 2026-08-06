package ai.devreport.backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.validation.MapBindingResult;
import org.springframework.web.bind.MissingServletRequestParameterException;

class GlobalExceptionHandlerTest {

	@Test
	void keepsFirstValidationMessageForEachField() {
		var bindingResult = new MapBindingResult(new HashMap<>(), "request");
		bindingResult.rejectValue("title", "required", "제목은 필수입니다.");
		bindingResult.rejectValue("title", "size", "제목이 너무 깁니다.");

		assertThat(GlobalExceptionHandler.validationDetails(bindingResult))
			.containsExactlyEntriesOf(java.util.Map.of("title", "제목은 필수입니다."));
	}

	@Test
	void returnsBadRequestForExpectedMvcRequestErrors() {
		var response = new GlobalExceptionHandler().handleBadRequest(
			new MissingServletRequestParameterException("title", "String"));

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().code()).isEqualTo("INVALID_REQUEST");
	}
}

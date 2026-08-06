package ai.devreport.backend.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;

import org.junit.jupiter.api.Test;
import org.springframework.validation.MapBindingResult;

class GlobalExceptionHandlerTest {

	@Test
	void keepsFirstValidationMessageForEachField() {
		var bindingResult = new MapBindingResult(new HashMap<>(), "request");
		bindingResult.rejectValue("title", "required", "제목은 필수입니다.");
		bindingResult.rejectValue("title", "size", "제목이 너무 깁니다.");

		assertThat(GlobalExceptionHandler.validationDetails(bindingResult))
			.containsExactlyEntriesOf(java.util.Map.of("title", "제목은 필수입니다."));
	}
}

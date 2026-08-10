package ai.devreport.backend.integration.ai;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record GenerationRequest(
	@NotEmpty List<@NotNull UUID> fileIds,
	@NotNull Map<String, Object> metadata,
	@NotBlank String instructions
) {
}

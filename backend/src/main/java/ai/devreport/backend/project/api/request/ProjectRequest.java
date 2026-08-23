package ai.devreport.backend.project.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ProjectRequest(@NotBlank @Size(max = 100) String name) {
}

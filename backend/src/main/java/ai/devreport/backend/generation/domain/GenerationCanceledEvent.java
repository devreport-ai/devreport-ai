package ai.devreport.backend.generation.domain;

import java.util.UUID;

public record GenerationCanceledEvent(UUID jobId) {
}

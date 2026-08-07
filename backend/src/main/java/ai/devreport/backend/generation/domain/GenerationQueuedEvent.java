package ai.devreport.backend.generation.domain;

import java.util.UUID;

public record GenerationQueuedEvent(UUID jobId) {
}

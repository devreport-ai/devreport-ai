package ai.devreport.backend.export.domain;

import java.util.UUID;

public record ReportExportQueuedEvent(UUID exportId) {
}

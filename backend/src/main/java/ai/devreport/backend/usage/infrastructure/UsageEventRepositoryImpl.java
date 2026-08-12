package ai.devreport.backend.usage.infrastructure;

import ai.devreport.backend.usage.domain.UsageEvent;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class UsageEventRepositoryImpl implements UsageEventRepositoryCustom {

	private static final String POSTGRESQL_INSERT = """
		INSERT INTO usage_events (
			id, event_type, deduplication_key, user_id, project_id, file_id, report_id,
			job_id, export_id, metadata, occurred_at
		) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSONB), ?)
		ON CONFLICT (deduplication_key) DO NOTHING
		""";
	private static final String H2_INSERT = """
		MERGE INTO usage_events AS target
		USING (VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? FORMAT JSON AS JSONB), ?))
			AS source(id, event_type, deduplication_key, user_id, project_id, file_id,
				report_id, job_id, export_id, metadata, occurred_at)
		ON target.deduplication_key = source.deduplication_key
		WHEN NOT MATCHED THEN
			INSERT (id, event_type, deduplication_key, user_id, project_id, file_id,
				report_id, job_id, export_id, metadata, occurred_at)
			VALUES (source.id, source.event_type, source.deduplication_key, source.user_id,
				source.project_id, source.file_id, source.report_id, source.job_id,
				source.export_id, source.metadata, source.occurred_at)
		""";

	private final JdbcTemplate jdbc;
	private final ObjectMapper objectMapper;

	UsageEventRepositoryImpl(JdbcTemplate jdbc, ObjectMapper objectMapper) {
		this.jdbc = jdbc;
		this.objectMapper = objectMapper;
	}

	@Override
	public int insertIgnoringDuplicate(UsageEvent event) {
		return jdbc.update(isPostgresql() ? POSTGRESQL_INSERT : H2_INSERT,
			event.getId(), event.getEventType().name(), event.getDeduplicationKey(), event.getUserId(),
			event.getProjectId(), event.getFileId(), event.getReportId(), event.getJobId(), event.getExportId(),
			metadata(event), event.getOccurredAt());
	}

	private boolean isPostgresql() {
		return Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
			"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
	}

	private String metadata(UsageEvent event) {
		try {
			return objectMapper.writeValueAsString(event.getMetadata());
		} catch (JacksonException exception) {
			throw new IllegalStateException("Usage event metadata could not be serialized", exception);
		}
	}
}

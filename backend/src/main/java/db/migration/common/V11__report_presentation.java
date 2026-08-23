package db.migration.common;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V11__report_presentation extends BaseJavaMigration {

	@Override
	public void migrate(Context context) throws Exception {
		boolean postgresql = "PostgreSQL".equalsIgnoreCase(
			context.getConnection().getMetaData().getDatabaseProductName());
		try (var statement = context.getConnection().createStatement()) {
			statement.execute("ALTER TABLE reports ADD COLUMN template_id VARCHAR(64)");
			statement.execute("ALTER TABLE reports ADD COLUMN template_version INTEGER");
			statement.execute("ALTER TABLE reports ADD COLUMN presentation_settings JSONB NOT NULL DEFAULT '{}'");
			statement.execute("""
				ALTER TABLE reports ADD CONSTRAINT ck_reports_template_pair
				CHECK ((template_id IS NULL AND template_version IS NULL)
					OR (template_id IS NOT NULL AND template_version IS NOT NULL AND template_version >= 1))%s
				""".formatted(postgresql ? " NOT VALID" : ""));
		}
	}
}

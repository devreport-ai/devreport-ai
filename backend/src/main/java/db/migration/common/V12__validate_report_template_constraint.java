package db.migration.common;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V12__validate_report_template_constraint extends BaseJavaMigration {

	@Override
	public void migrate(Context context) throws Exception {
		if (!"PostgreSQL".equalsIgnoreCase(
			context.getConnection().getMetaData().getDatabaseProductName())) {
			return;
		}
		try (var statement = context.getConnection().createStatement()) {
			statement.execute("ALTER TABLE reports VALIDATE CONSTRAINT ck_reports_template_pair");
		}
	}
}

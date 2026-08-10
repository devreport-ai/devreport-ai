package db.migration.common;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

public class V10__generation_job_cancellation extends BaseJavaMigration {

	@Override
	public void migrate(Context context) throws Exception {
		List<String> constraints = new ArrayList<>();
		try (Statement statement = context.getConnection().createStatement();
			ResultSet results = statement.executeQuery("""
				SELECT tc.constraint_name, cc.check_clause
				FROM information_schema.table_constraints tc
				JOIN information_schema.check_constraints cc
				  ON cc.constraint_catalog = tc.constraint_catalog
				 AND cc.constraint_schema = tc.constraint_schema
				 AND cc.constraint_name = tc.constraint_name
				WHERE LOWER(tc.table_name) = 'generation_jobs'
				  AND tc.constraint_type = 'CHECK'
				""")) {
			while (results.next()) {
				String clause = results.getString("check_clause").toLowerCase();
				if (clause.contains("'pending'") || clause.contains("'queued'")) {
					constraints.add(results.getString("constraint_name"));
				}
			}
		}
		try (Statement statement = context.getConnection().createStatement()) {
			for (String constraint : constraints) {
				statement.execute("ALTER TABLE generation_jobs DROP CONSTRAINT \""
					+ constraint.replace("\"", "\"\"") + "\"");
			}
			statement.execute("""
				ALTER TABLE generation_jobs ADD CONSTRAINT ck_generation_jobs_status
				CHECK (status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED', 'CANCELED'))
				""");
			statement.execute("""
				ALTER TABLE generation_jobs ADD CONSTRAINT ck_generation_jobs_stage
				CHECK (current_stage IN ('QUEUED', 'CALLING_AI', 'COMPLETED', 'FAILED', 'CANCELED'))
				""");
		}
	}
}

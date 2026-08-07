package ai.devreport.backend.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:project-repository;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"auth.jwt-secret=test-secret-that-is-at-least-32-bytes-long"
})
@Transactional
class ProjectRepositoryTest {

	@Autowired
	ProjectRepository projects;

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void findsOnlyProjectsOwnedByUser() {
		UUID ownerId = insertUser("owner-repository@example.com");
		UUID otherId = insertUser("other-repository@example.com");
		Project owned = projects.save(new Project(ownerId, "내 프로젝트"));
		Project other = projects.save(new Project(otherId, "다른 프로젝트"));

		assertThat(projects.findAllByOwnerId(ownerId,
			PageRequest.of(0, 20, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id")))).getContent())
			.extracting(Project::getId)
			.containsExactly(owned.getId());
		assertThat(projects.findByIdAndOwnerId(owned.getId(), ownerId)).contains(owned);
		assertThat(projects.findByIdAndOwnerId(other.getId(), ownerId)).isEmpty();
	}

	private UUID insertUser(String email) {
		UUID id = UUID.randomUUID();
		jdbc.update("""
			INSERT INTO app_users (id, email, password_hash, name, created_at)
			VALUES (?, ?, ?, ?, ?)
			""", id, email, "unused", "사용자", Instant.now());
		return id;
	}
}

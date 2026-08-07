package ai.devreport.backend.project;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
class ProjectService {

	private final ProjectRepository projects;

	ProjectService(ProjectRepository projects) {
		this.projects = projects;
	}

	Project create(UUID ownerId, String name) {
		return projects.save(new Project(ownerId, name));
	}

	@Transactional(readOnly = true)
	List<Project> list(UUID ownerId) {
		return projects.findAllByOwnerIdOrderByUpdatedAtDesc(ownerId);
	}

	@Transactional(readOnly = true)
	Project get(UUID ownerId, UUID projectId) {
		return ownedProject(ownerId, projectId);
	}

	Project update(UUID ownerId, UUID projectId, String name) {
		Project project = ownedProject(ownerId, projectId);
		project.rename(name);
		return project;
	}

	void delete(UUID ownerId, UUID projectId) {
		projects.delete(ownedProject(ownerId, projectId));
	}

	private Project ownedProject(UUID ownerId, UUID projectId) {
		return projects.findByIdAndOwnerId(projectId, ownerId)
			.orElseThrow(ProjectNotFoundException::new);
	}
}

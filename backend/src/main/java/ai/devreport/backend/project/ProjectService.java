package ai.devreport.backend.project;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
	Page<Project> list(UUID ownerId, int page, int size) {
		Sort sort = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
		return projects.findAllByOwnerIdAndDeletedAtIsNull(ownerId, PageRequest.of(page, size, sort));
	}

	@Transactional(readOnly = true)
	Page<Project> trash(UUID ownerId, int page, int size) {
		Sort sort = Sort.by(Sort.Order.desc("deletedAt"), Sort.Order.desc("id"));
		return projects.findAllByOwnerIdAndDeletedAtIsNotNull(ownerId, PageRequest.of(page, size, sort));
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
		ownedProject(ownerId, projectId).delete();
	}

	Project restore(UUID ownerId, UUID projectId) {
		Project project = projects.findByIdAndOwnerIdAndDeletedAtIsNotNull(projectId, ownerId)
			.orElseThrow(ProjectNotFoundException::new);
		project.restore();
		return project;
	}

	private Project ownedProject(UUID ownerId, UUID projectId) {
		return projects.findByIdAndOwnerIdAndDeletedAtIsNull(projectId, ownerId)
			.orElseThrow(ProjectNotFoundException::new);
	}
}

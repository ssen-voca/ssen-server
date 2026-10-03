package com.ssen.voca.user;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	List<AppUser> findAllByNameKeyAndRole(String nameKey, String role);

	List<AppUser> findAllByClassroomIdAndNameKeyAndRole(Long classroomId, String nameKey, String role);

	Optional<AppUser> findByEmail(String email);

	boolean existsByEmail(String email);
}

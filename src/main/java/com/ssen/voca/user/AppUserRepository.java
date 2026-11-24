package com.ssen.voca.user;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	List<AppUser> findAllBySchoolKeyAndNameKeyAndRole(String schoolKey, String nameKey, String role);

	List<AppUser> findAllBySchoolKeyAndNameKeyAndRoleIn(String schoolKey, String nameKey, Collection<String> roles);

	int countByClassroomIdAndRole(Long classroomId, String role);

	List<AppUser> findAllByClassroomIdAndRoleOrderBySchoolKeyAscNameKeyAscIdAsc(Long classroomId, String role);

	Optional<AppUser> findByIdAndClassroomIdAndRole(Long id, Long classroomId, String role);

	// 영속성 컨텍스트에 남은 옛 소속이 읽히지 않도록 먼저 내보내고 비운다.
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Query("update AppUser u set u.classroomId = null where u.classroomId = :classroomId")
	int clearClassroom(@Param("classroomId") Long classroomId);
}

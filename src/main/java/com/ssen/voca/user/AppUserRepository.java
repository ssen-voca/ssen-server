package com.ssen.voca.user;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

	List<AppUser> findAllBySchoolKeyAndNameKeyAndRole(String schoolKey, String nameKey, String role);

	List<AppUser> findAllBySchoolKeyAndNameKeyAndRoleIn(String schoolKey, String nameKey, Collection<String> roles);
}

package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class TeacherSchemaTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void teacherIsSavedWithEmailAndRole() {
		appUserRepository.saveAndFlush(AppUser.teacher("스키마교사", "스키마교사", "schema-teacher@example.com", "hash"));

		AppUser found = appUserRepository.findByEmail("schema-teacher@example.com").orElseThrow();
		assertThat(found.getRole()).isEqualTo(AppUser.TEACHER);
		assertThat(found.getEmail()).isEqualTo("schema-teacher@example.com");
		assertThat(appUserRepository.existsByEmail("schema-teacher@example.com")).isTrue();
		assertThat(appUserRepository.existsByEmail("nobody@example.com")).isFalse();
	}

	@Test
	void teacherRowWithoutEmailViolatesCheckConstraint() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO app_user (name, name_key, secret_hash, role) VALUES ('교사', '교사', 'h', 'TEACHER')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void duplicateEmailViolatesUniqueIndex() {
		appUserRepository.saveAndFlush(AppUser.teacher("중복가", "중복가", "dup-schema@example.com", "h"));

		assertThatThrownBy(() -> appUserRepository.saveAndFlush(
				AppUser.teacher("중복나", "중복나", "dup-schema@example.com", "h")))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void studentsWithoutEmailCanCoexist() {
		appUserRepository.saveAndFlush(new AppUser("무이메일가", "스키마무이메일", "h"));
		appUserRepository.saveAndFlush(new AppUser("무이메일나", "스키마무이메일", "h"));

		assertThat(appUserRepository.findAllByNameKeyAndRole("스키마무이메일", AppUser.STUDENT)).hasSize(2);
	}

	@Test
	void roleFilterSeparatesTeacherAndStudentWithSameNameKey() {
		appUserRepository.saveAndFlush(AppUser.teacher("스키마동명", "스키마동명", "same-name@example.com", "h"));
		appUserRepository.saveAndFlush(new AppUser("스키마동명", "스키마동명", "h"));

		assertThat(appUserRepository.findAllByNameKeyAndRole("스키마동명", AppUser.STUDENT))
				.extracting(AppUser::getRole).containsExactly(AppUser.STUDENT);
		assertThat(appUserRepository.findAllByNameKeyAndRole("스키마동명", AppUser.TEACHER))
				.extracting(AppUser::getRole).containsExactly(AppUser.TEACHER);
	}
}

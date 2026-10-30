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
		appUserRepository.saveAndFlush(new AppUser("무이메일가", "스키마무이메일", "h", "스키마학교", "스키마학교"));
		appUserRepository.saveAndFlush(new AppUser("무이메일나", "스키마무이메일", "h", "스키마학교", "스키마학교"));

		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRole(
				"스키마학교", "스키마무이메일", AppUser.STUDENT)).hasSize(2);
	}

	@Test
	void roleFilterSeparatesTeacherAndStudentWithSameNameKey() {
		appUserRepository.saveAndFlush(AppUser.teacher("스키마동명", "스키마동명", "same-name@example.com", "h"));
		appUserRepository.saveAndFlush(new AppUser("스키마동명", "스키마동명", "h", "스키마학교", "스키마학교"));

		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRole(
				"스키마학교", "스키마동명", AppUser.STUDENT))
				.extracting(AppUser::getRole).containsExactly(AppUser.STUDENT);
		// 교사는 학교가 없으므로 학교 범위 학생 조회에도, 교사 역할 조회에도 잡히지 않는다.
		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRole(
				"스키마학교", "스키마동명", AppUser.TEACHER)).isEmpty();
	}
}

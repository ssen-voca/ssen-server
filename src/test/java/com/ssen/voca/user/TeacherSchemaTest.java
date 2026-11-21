package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
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
	void teacherIsSavedWithSchoolIdentityAndNoEmail() {
		AppUser saved = appUserRepository.saveAndFlush(
				AppUser.teacher("스키마교사", "스키마교사", "hash", "스키마 학원", "스키마학원"));

		assertThat(saved.getRole()).isEqualTo(AppUser.TEACHER);
		assertThat(saved.getEmail()).isNull();
		assertThat(saved.getSchoolKey()).isEqualTo("스키마학원");
		assertThat(saved.getClassroomId()).isNull();
	}

	@Test
	void teacherRowWithoutEmailAndSchoolViolatesCheckConstraint() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO app_user (name, name_key, secret_hash, role) VALUES ('교사', '교사', 'h', 'TEACHER')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void legacyTeacherRowWithEmailOnlyIsStillAllowed() {
		jdbcTemplate.update("INSERT INTO app_user (name, name_key, secret_hash, email, role) "
				+ "VALUES ('옛교사', '옛교사', 'h', 'legacy-schema@example.com', 'TEACHER')");

		assertThat(jdbcTemplate.queryForObject(
				"SELECT count(*) FROM app_user WHERE email = 'legacy-schema@example.com'", Integer.class)).isEqualTo(1);
	}

	@Test
	void roleFilterSeparatesTeacherAndStudentWithSameSchoolAndName() {
		appUserRepository.saveAndFlush(AppUser.teacher("스키마동명", "스키마동명", "h", "스키마학교", "스키마학교"));
		appUserRepository.saveAndFlush(new AppUser("스키마동명", "스키마동명", "h", "스키마학교", "스키마학교"));

		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRole("스키마학교", "스키마동명", AppUser.STUDENT))
				.extracting(AppUser::getRole).containsExactly(AppUser.STUDENT);
		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRole("스키마학교", "스키마동명", AppUser.TEACHER))
				.extracting(AppUser::getRole).containsExactly(AppUser.TEACHER);
		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRoleIn(
				"스키마학교", "스키마동명", List.of(AppUser.STUDENT, AppUser.TEACHER)))
				.extracting(AppUser::getRole).containsExactlyInAnyOrder(AppUser.STUDENT, AppUser.TEACHER);
	}

	@Test
	void duplicateEmailViolatesUniqueIndex() {
		String insert = "INSERT INTO app_user (name, name_key, secret_hash, email, role) "
				+ "VALUES (?, ?, 'h', 'dup-schema@example.com', 'TEACHER')";
		jdbcTemplate.update(insert, "중복가", "중복가");

		assertThatThrownBy(() -> jdbcTemplate.update(insert, "중복나", "중복나"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void studentsWithoutEmailCanCoexist() {
		String insert = "INSERT INTO app_user (name, name_key, secret_hash, school, school_key, role) "
				+ "VALUES (?, '스키마무이메일', 'h', '스키마학교', '스키마학교', 'STUDENT')";
		jdbcTemplate.update(insert, "무이메일가");
		jdbcTemplate.update(insert, "무이메일나");

		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRole("스키마학교", "스키마무이메일", AppUser.STUDENT))
				.hasSize(2);
	}
}

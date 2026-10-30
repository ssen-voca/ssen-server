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
class StudentSchemaTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void studentWithoutSchoolViolatesCheckConstraint() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO app_user (name, name_key, secret_hash, role) VALUES ('학생', '학생', 'h', 'STUDENT')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void studentWithSchoolAndNoClassroomIsSaved() {
		AppUser saved = appUserRepository.saveAndFlush(new AppUser("학생", "스키마학생", "h", "스키마 학교", "스키마학교"));

		assertThat(saved.getSchool()).isEqualTo("스키마 학교");
		assertThat(saved.getSchoolKey()).isEqualTo("스키마학교");
		assertThat(saved.getClassroomId()).isNull();
		assertThat(saved.getRole()).isEqualTo(AppUser.STUDENT);
	}

	@Test
	void teacherDoesNotNeedASchoolOrClassroom() {
		AppUser saved = appUserRepository.saveAndFlush(
				AppUser.teacher("교사", "스키마교사학생", "schema-student-teacher@example.com", "h"));

		assertThat(saved.getSchool()).isNull();
		assertThat(saved.getSchoolKey()).isNull();
		assertThat(saved.getClassroomId()).isNull();
	}

	@Test
	void classCodeColumnIsGone() {
		Integer columns = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM information_schema.columns WHERE table_name = 'app_user' AND column_name = 'class_code'",
				Integer.class);

		assertThat(columns).isZero();
	}
}

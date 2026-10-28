package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.support.ClassroomFixture;
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
	private ClassroomFixture fixture;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void studentWithoutClassroomViolatesCheckConstraint() {
		assertThatThrownBy(() -> jdbcTemplate.update(
				"INSERT INTO app_user (name, name_key, secret_hash, role) VALUES ('학생', '학생', 'h', 'STUDENT')"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void studentWithClassroomIsSaved() {
		Classroom classroom = fixture.newClassroom();

		AppUser saved = appUserRepository.saveAndFlush(new AppUser("학생", "스키마학생", "h", classroom.getId()));

		assertThat(saved.getClassroomId()).isEqualTo(classroom.getId());
		assertThat(saved.getRole()).isEqualTo(AppUser.STUDENT);
	}

	@Test
	void teacherDoesNotNeedAClassroom() {
		AppUser saved = appUserRepository.saveAndFlush(
				AppUser.teacher("교사", "스키마교사학생", "schema-student-teacher@example.com", "h"));

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

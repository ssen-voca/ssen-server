package com.ssen.voca.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class TeacherAccountSeederTest {

	@Autowired
	private TeacherAccountSeeder seeder;

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	private String school;

	@BeforeEach
	void newSchool() {
		school = "시더학원-" + UUID.randomUUID();
	}

	private List<AppUser> teachers(String name) {
		return appUserRepository.findAllBySchoolKeyAndNameKeyAndRole(
				Schools.key(school), Names.key(name), AppUser.TEACHER);
	}

	@Test
	void createsTeachersWithDisplayValuesKeysAndHashedPin() {
		seeder.seed(List.of(
				new TeacherAccounts.Entry("  " + school.toUpperCase() + " ", " 김  선생 ", "1234"),
				new TeacherAccounts.Entry(school, "박선생", "5678")));

		AppUser kim = teachers("김 선생").get(0);
		assertThat(kim.getRole()).isEqualTo(AppUser.TEACHER);
		assertThat(kim.getName()).isEqualTo("김  선생");
		assertThat(kim.getSchool()).isEqualTo(Schools.display(school.toUpperCase()));
		assertThat(kim.getSchoolKey()).isEqualTo(Schools.key(school));
		assertThat(kim.getNameKey()).isEqualTo("김 선생");
		assertThat(kim.getEmail()).isNull();
		assertThat(kim.getClassroomId()).isNull();
		assertThat(kim.getSecretHash()).isNotEqualTo("1234");
		assertThat(passwordEncoder.matches("1234", kim.getSecretHash())).isTrue();
		assertThat(passwordEncoder.matches("5678", teachers("박선생").get(0).getSecretHash())).isTrue();
	}

	@Test
	void secondRunIsIdempotentAndDoesNotRewriteTheHash() {
		List<TeacherAccounts.Entry> entries = List.of(new TeacherAccounts.Entry(school, "김선생", "1234"));
		seeder.seed(entries);
		AppUser first = teachers("김선생").get(0);
		String hash = first.getSecretHash();

		seeder.seed(entries);

		List<AppUser> after = teachers("김선생");
		assertThat(after).hasSize(1);
		assertThat(after.get(0).getId()).isEqualTo(first.getId());
		assertThat(after.get(0).getSecretHash()).isEqualTo(hash);
	}

	@Test
	void changingThePinInConfigRotatesTheHash() {
		seeder.seed(List.of(new TeacherAccounts.Entry(school, "김선생", "1234")));
		Long id = teachers("김선생").get(0).getId();

		seeder.seed(List.of(new TeacherAccounts.Entry(school, "김선생", "9999")));

		List<AppUser> after = teachers("김선생");
		assertThat(after).hasSize(1);
		assertThat(after.get(0).getId()).isEqualTo(id);
		assertThat(passwordEncoder.matches("9999", after.get(0).getSecretHash())).isTrue();
		assertThat(passwordEncoder.matches("1234", after.get(0).getSecretHash())).isFalse();
	}

	@Test
	void studentsWithTheSameSchoolAndNameAreLeftAlone() {
		AppUser student = appUserRepository.saveAndFlush(new AppUser(
				"김선생", "김선생", passwordEncoder.encode("1111"), school, Schools.key(school)));
		String studentHash = student.getSecretHash();

		seeder.seed(List.of(new TeacherAccounts.Entry(school, "김선생", "1234")));

		AppUser reloaded = appUserRepository.findById(student.getId()).orElseThrow();
		assertThat(reloaded.getRole()).isEqualTo(AppUser.STUDENT);
		assertThat(reloaded.getSecretHash()).isEqualTo(studentHash);
		assertThat(teachers("김선생")).hasSize(1);
	}

	@Test
	void teachersMissingFromTheConfigAreNotTouched() {
		seeder.seed(List.of(
				new TeacherAccounts.Entry(school, "김선생", "1234"),
				new TeacherAccounts.Entry(school, "박선생", "5678")));

		seeder.seed(List.of(new TeacherAccounts.Entry(school, "김선생", "1234")));

		assertThat(teachers("박선생")).hasSize(1);
		assertThat(passwordEncoder.matches("5678", teachers("박선생").get(0).getSecretHash())).isTrue();
	}

	@Test
	void emptyConfigDoesNothing() {
		long before = appUserRepository.count();

		seeder.seed(List.of());

		assertThat(appUserRepository.count()).isEqualTo(before);
	}

	@Test
	void startupFailsWhenAStudentWithTheSameSchoolNameAndPinExists() {
		appUserRepository.saveAndFlush(new AppUser(
				"김선생", "김선생", passwordEncoder.encode("1234"), school, Schools.key(school)));

		assertThatThrownBy(() -> seeder.seed(List.of(
				new TeacherAccounts.Entry(school, "박선생", "5678"),
				new TeacherAccounts.Entry(school, "김선생", "1234"))))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("2번")
				.satisfies(e -> assertThat(e.getMessage()).doesNotContain("1234"));
		assertThat(teachers("김선생")).isEmpty();
	}

	@Test
	void everyDuplicateTeacherRowWithAStalePinIsRotated() {
		String schoolKey = Schools.key(school);
		appUserRepository.saveAndFlush(AppUser.teacher("김선생", "김선생", passwordEncoder.encode("1111"), school, schoolKey));
		appUserRepository.saveAndFlush(AppUser.teacher("김선생", "김선생", passwordEncoder.encode("2222"), school, schoolKey));

		seeder.seed(List.of(new TeacherAccounts.Entry(school, "김선생", "9999")));

		assertThat(teachers("김선생")).hasSize(2)
				.allSatisfy(t -> assertThat(passwordEncoder.matches("9999", t.getSecretHash())).isTrue());
	}
}

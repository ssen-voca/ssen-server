package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.support.ClassroomFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AppUserRepositoryTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private ClassroomFixture fixture;

	@Test
	void savesAndFindsAllByNameKey() {
		Classroom classroom = fixture.newClassroom();
		appUserRepository.save(new AppUser("김학생", "repo-김학생", "hash-1", classroom.getId()));
		appUserRepository.save(new AppUser("김학생", "repo-김학생", "hash-2", classroom.getId()));

		assertThat(appUserRepository.findAllByClassroomIdAndNameKeyAndRole(
				classroom.getId(), "repo-김학생", AppUser.STUDENT))
				.extracting(AppUser::getSecretHash, AppUser::getRole)
				.containsExactlyInAnyOrder(
						tuple("hash-1", "STUDENT"),
						tuple("hash-2", "STUDENT"));
	}

	@Test
	void findAllByNameKeyReturnsEmptyForUnknownName() {
		Classroom classroom = fixture.newClassroom();

		assertThat(appUserRepository.findAllByClassroomIdAndNameKeyAndRole(
				classroom.getId(), "repo-nobody", AppUser.STUDENT)).isEmpty();
	}
}

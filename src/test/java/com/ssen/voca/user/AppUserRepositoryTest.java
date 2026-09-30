package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AppUserRepositoryTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@Test
	void savesAndFindsAllByNameKey() {
		appUserRepository.save(new AppUser("김학생", "repo-김학생", "hash-1"));
		appUserRepository.save(new AppUser("김학생", "repo-김학생", "hash-2"));

		assertThat(appUserRepository.findAllByNameKey("repo-김학생"))
				.extracting(AppUser::getPinHash, AppUser::getRole)
				.containsExactlyInAnyOrder(
						tuple("hash-1", "STUDENT"),
						tuple("hash-2", "STUDENT"));
	}

	@Test
	void findAllByNameKeyReturnsEmptyForUnknownName() {
		assertThat(appUserRepository.findAllByNameKey("repo-nobody")).isEmpty();
	}
}

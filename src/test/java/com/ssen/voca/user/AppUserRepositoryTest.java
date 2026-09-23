package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;

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
	void savesAndFindsByEmail() {
		AppUser user = new AppUser("김학생", "student@example.com", "hashed-password");

		appUserRepository.save(user);

		assertThat(appUserRepository.findByEmail("student@example.com"))
				.isPresent()
				.get()
				.extracting(AppUser::getName, AppUser::getRole)
				.containsExactly("김학생", "STUDENT");
	}

	@Test
	void existsByEmailReturnsFalseForUnknownEmail() {
		assertThat(appUserRepository.existsByEmail("nobody@example.com")).isFalse();
	}
}

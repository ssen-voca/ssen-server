package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class StudentSchoolRepositoryTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@Test
	void studentLookupIsScopedToTheSchoolNameAndStudentRole() {
		appUserRepository.saveAndFlush(new AppUser("가", "학교조회동명", "h", "조회학교A", "조회학교a"));
		appUserRepository.saveAndFlush(new AppUser("나", "학교조회동명", "h", "조회학교B", "조회학교b"));
		appUserRepository.saveAndFlush(new AppUser("다", "학교조회다른이름", "h", "조회학교A", "조회학교a"));
		// 같은 이름 키의 교사는 학생 조회에 섞이지 않는다.
		appUserRepository.saveAndFlush(AppUser.teacher("교사", "학교조회동명", "school-scoped-teacher@example.com", "h"));

		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRole("조회학교a", "학교조회동명", AppUser.STUDENT))
				.extracting(AppUser::getName).containsExactly("가");
		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRole("조회학교b", "학교조회동명", AppUser.STUDENT))
				.extracting(AppUser::getName).containsExactly("나");
		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRole("조회학교a", "없는이름", AppUser.STUDENT))
				.isEmpty();
		assertThat(appUserRepository.findAllBySchoolKeyAndNameKeyAndRole("없는학교", "학교조회동명", AppUser.STUDENT))
				.isEmpty();
	}
}

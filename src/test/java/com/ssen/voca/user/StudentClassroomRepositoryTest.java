package com.ssen.voca.user;

import static org.assertj.core.api.Assertions.assertThat;

import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.classroom.ClassroomRepository;
import com.ssen.voca.support.ClassroomFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class StudentClassroomRepositoryTest {

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private ClassroomRepository classroomRepository;

	@Autowired
	private ClassroomFixture fixture;

	@Test
	void findByCodeReturnsTheClassroomAndEmptyForUnknownCodes() {
		Classroom classroom = fixture.newClassroom();

		assertThat(classroomRepository.findByCode(classroom.getCode()))
				.get().extracting(Classroom::getId).isEqualTo(classroom.getId());
		assertThat(classroomRepository.findByCode("ZZZZZZ")).isEmpty();
	}

	@Test
	void studentLookupIsScopedToTheClassroomAndTheStudentRole() {
		Classroom a = fixture.newClassroom();
		Classroom b = fixture.newClassroom();
		appUserRepository.saveAndFlush(new AppUser("가", "수업조회동명", "h", a.getId()));
		appUserRepository.saveAndFlush(new AppUser("나", "수업조회동명", "h", b.getId()));
		// 같은 이름 키의 교사는 학생 조회에 섞이지 않는다.
		appUserRepository.saveAndFlush(AppUser.teacher("교사", "수업조회동명", "scoped-teacher@example.com", "h"));

		assertThat(appUserRepository.findAllByClassroomIdAndNameKeyAndRole(a.getId(), "수업조회동명", AppUser.STUDENT))
				.extracting(AppUser::getName).containsExactly("가");
		assertThat(appUserRepository.findAllByClassroomIdAndNameKeyAndRole(b.getId(), "수업조회동명", AppUser.STUDENT))
				.extracting(AppUser::getName).containsExactly("나");
		assertThat(appUserRepository.findAllByClassroomIdAndNameKeyAndRole(a.getId(), "다른이름", AppUser.STUDENT))
				.isEmpty();
	}
}

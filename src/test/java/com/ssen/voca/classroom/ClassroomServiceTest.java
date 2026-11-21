package com.ssen.voca.classroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import com.ssen.voca.support.TeacherFixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ClassroomServiceTest {

	@Autowired
	private ClassroomService classroomService;

	@Autowired
	private TeacherFixture teacherFixture;

	@MockitoSpyBean
	private ClassCodeGenerator codeGenerator;

	private Long newTeacherId() {
		return teacherFixture.create("서비스교사").id();
	}

	@Test
	void createRetriesWhenTheCodeIsTaken() {
		Long teacherId = newTeacherId();
		doReturn("AAAAAA").when(codeGenerator).generate();
		Classroom first = classroomService.create(teacherId, "첫 수업");
		assertThat(first.getCode()).isEqualTo("AAAAAA");

		doReturn("AAAAAA", "AAAAAA", "BBBBBB").when(codeGenerator).generate();
		Classroom second = classroomService.create(teacherId, "둘째 수업");

		assertThat(second.getCode()).isEqualTo("BBBBBB");
	}

	@Test
	void createGivesUpAfterFiveCollisions() {
		Long teacherId = newTeacherId();
		doReturn("CCCCCC").when(codeGenerator).generate();
		classroomService.create(teacherId, "선점 수업");

		assertThatThrownBy(() -> classroomService.create(teacherId, "충돌 수업"))
				.isInstanceOf(IllegalStateException.class);
	}

	@Test
	void listOfReturnsOnlyOwnClassroomsNewestFirst() {
		Long teacherA = newTeacherId();
		Long teacherB = newTeacherId();
		classroomService.create(teacherA, "A-1");
		classroomService.create(teacherB, "B-1");
		classroomService.create(teacherA, "A-2");

		assertThat(classroomService.listOf(teacherA)).extracting(Classroom::getName).containsExactly("A-2", "A-1");
		assertThat(classroomService.listOf(teacherB)).extracting(Classroom::getName).containsExactly("B-1");
	}
}

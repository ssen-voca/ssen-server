package com.ssen.voca.support;

import com.ssen.voca.classroom.ClassCodeGenerator;
import com.ssen.voca.classroom.Classroom;
import com.ssen.voca.classroom.ClassroomRepository;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;

/** 테스트용 교사와 수업을 만든다. 호출할 때마다 서로 다른 교사 이메일·참여코드가 나온다. */
@Component
public class ClassroomFixture {

	private static final AtomicInteger SEQUENCE = new AtomicInteger();

	private final AppUserRepository appUserRepository;
	private final ClassroomRepository classroomRepository;
	private final ClassCodeGenerator codeGenerator;

	public ClassroomFixture(
			AppUserRepository appUserRepository,
			ClassroomRepository classroomRepository,
			ClassCodeGenerator codeGenerator) {
		this.appUserRepository = appUserRepository;
		this.classroomRepository = classroomRepository;
		this.codeGenerator = codeGenerator;
	}

	public Classroom newClassroom() {
		int n = SEQUENCE.incrementAndGet();
		AppUser teacher = appUserRepository.save(AppUser.teacher(
				"픽스처교사" + n, "픽스처교사" + n, "fixture-" + UUID.randomUUID() + "@example.com", "hash"));
		String code;
		do {
			code = codeGenerator.generate();
		} while (classroomRepository.existsByCode(code));
		return classroomRepository.save(new Classroom(teacher.getId(), "픽스처수업" + n, code));
	}

	/** 트랜잭션 밖에서 만든 수업과 교사를 지운다 (그 수업에 속한 학생을 먼저 지운 뒤 호출한다). */
	public void deleteClassroomAndTeacher(Classroom classroom) {
		classroomRepository.deleteById(classroom.getId());
		appUserRepository.deleteById(classroom.getTeacherId());
	}
}

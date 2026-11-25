package com.ssen.voca.classroom;

import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ClassroomService {

	private static final int MAX_CODE_ATTEMPTS = 5;

	private final ClassroomRepository classroomRepository;
	private final AppUserRepository appUserRepository;
	private final ClassCodeGenerator codeGenerator;
	private final JdbcTemplate jdbc;

	public ClassroomService(
			ClassroomRepository classroomRepository,
			AppUserRepository appUserRepository,
			ClassCodeGenerator codeGenerator,
			JdbcTemplate jdbc) {
		this.classroomRepository = classroomRepository;
		this.appUserRepository = appUserRepository;
		this.codeGenerator = codeGenerator;
		this.jdbc = jdbc;
	}

	@Transactional
	public Classroom create(Long teacherId, String name) {
		return classroomRepository.save(new Classroom(teacherId, name, newUniqueCode()));
	}

	@Transactional(readOnly = true)
	public List<Classroom> listOf(Long teacherId) {
		return classroomRepository.findAllByTeacherIdOrderByIdDesc(teacherId);
	}

	@Transactional(readOnly = true)
	public int studentCount(Long classroomId) {
		return appUserRepository.countByClassroomIdAndRole(classroomId, AppUser.STUDENT);
	}

	/** name, joinable 중 null이 아닌 것만 바꾼다. */
	@Transactional
	public Classroom update(Long teacherId, Long classroomId, String name, Boolean joinable) {
		Classroom classroom = owned(teacherId, classroomId);
		if (name != null) {
			classroom.rename(name);
		}
		if (joinable != null) {
			classroom.setJoinable(joinable);
		}
		return classroom;
	}

	/** 새 참여코드를 발급한다. 옛 코드는 바로 쓸 수 없고, 이미 들어온 학생의 소속은 그대로다. */
	@Transactional
	public Classroom reissueCode(Long teacherId, Long classroomId) {
		Classroom classroom = owned(teacherId, classroomId);
		classroom.changeCode(newUniqueCode());
		return classroom;
	}

	@Transactional(readOnly = true)
	public List<AppUser> students(Long teacherId, Long classroomId) {
		owned(teacherId, classroomId);
		return appUserRepository.findAllByClassroomIdAndRoleOrderBySchoolKeyAscNameKeyAscIdAsc(
				classroomId, AppUser.STUDENT);
	}

	/** 학생의 소속만 비운다. 계정·북마크·개인 단어는 그대로다. */
	@Transactional
	public void removeStudent(Long teacherId, Long classroomId, Long studentId) {
		owned(teacherId, classroomId);
		AppUser student = appUserRepository.findByIdAndClassroomIdAndRole(studentId, classroomId, AppUser.STUDENT)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "학생을 찾을 수 없어요."));
		student.leaveClassroom();
	}

	/** 학생 소속 해제 → 이 수업의 단어 삭제(북마크 연쇄) → 수업 삭제(lesson 연쇄). lesson을 지워도 word 행은 남기 때문에 먼저 지운다. */
	@Transactional
	public void delete(Long teacherId, Long classroomId) {
		owned(teacherId, classroomId);
		appUserRepository.clearClassroom(classroomId);
		jdbc.update("DELETE FROM word WHERE id IN (SELECT lw.word_id FROM lesson_word lw "
				+ "JOIN lesson l ON l.id = lw.lesson_id WHERE l.classroom_id = ?)", classroomId);
		jdbc.update("DELETE FROM classroom WHERE id = ?", classroomId);
	}

	// 남의 수업은 없는 수업과 같은 404로 답해 존재 여부를 드러내지 않는다.
	private Classroom owned(Long teacherId, Long classroomId) {
		return classroomRepository.findByIdAndTeacherId(classroomId, teacherId)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "수업을 찾을 수 없어요."));
	}

	private String newUniqueCode() {
		for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
			String code = codeGenerator.generate();
			if (!classroomRepository.existsByCode(code)) {
				// ponytail: exists 확인과 저장 사이의 경합은 UNIQUE 제약이 막고 그 요청만 500이 된다. 31^6 가지라 사실상 일어나지 않는다.
				return code;
			}
		}
		throw new IllegalStateException("참여 코드를 만들지 못했어요");
	}
}

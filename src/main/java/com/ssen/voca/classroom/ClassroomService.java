package com.ssen.voca.classroom;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ClassroomService {

	private static final int MAX_CODE_ATTEMPTS = 5;

	private final ClassroomRepository classroomRepository;
	private final ClassCodeGenerator codeGenerator;

	public ClassroomService(ClassroomRepository classroomRepository, ClassCodeGenerator codeGenerator) {
		this.classroomRepository = classroomRepository;
		this.codeGenerator = codeGenerator;
	}

	@Transactional
	public Classroom create(Long teacherId, String name) {
		for (int attempt = 0; attempt < MAX_CODE_ATTEMPTS; attempt++) {
			String code = codeGenerator.generate();
			if (!classroomRepository.existsByCode(code)) {
				// ponytail: exists 확인과 저장 사이의 경합은 UNIQUE 제약이 막고 그 요청만 500이 된다. 31^6 가지라 사실상 일어나지 않는다.
				return classroomRepository.save(new Classroom(teacherId, name, code));
			}
		}
		throw new IllegalStateException("참여 코드를 만들지 못했어요");
	}

	@Transactional(readOnly = true)
	public List<Classroom> listOf(Long teacherId) {
		return classroomRepository.findAllByTeacherIdOrderByIdDesc(teacherId);
	}
}

package com.ssen.voca.classroom;

import com.ssen.voca.classroom.dto.ClassroomResponse;
import com.ssen.voca.classroom.dto.CreateClassroomRequest;
import com.ssen.voca.classroom.dto.StudentResponse;
import com.ssen.voca.classroom.dto.UpdateClassroomRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/teacher/classrooms")
public class TeacherClassroomController {

	private final ClassroomService classroomService;

	public TeacherClassroomController(ClassroomService classroomService) {
		this.classroomService = classroomService;
	}

	@PostMapping
	public ResponseEntity<ClassroomResponse> create(
			Authentication authentication, @Valid @RequestBody CreateClassroomRequest request) {
		Classroom classroom = classroomService.create(teacherId(authentication), request.name());
		return ResponseEntity.status(HttpStatus.CREATED).body(response(classroom));
	}

	@GetMapping
	public List<ClassroomResponse> list(Authentication authentication) {
		return classroomService.listOf(teacherId(authentication)).stream().map(this::response).toList();
	}

	@PatchMapping("/{id}")
	public ClassroomResponse update(
			Authentication authentication, @PathVariable Long id, @Valid @RequestBody UpdateClassroomRequest request) {
		if (request.name() == null && request.joinable() == null) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "바꿀 내용을 입력해 주세요.");
		}
		return response(classroomService.update(teacherId(authentication), id, request.name(), request.joinable()));
	}

	@PostMapping("/{id}/code")
	public ClassroomResponse reissueCode(Authentication authentication, @PathVariable Long id) {
		return response(classroomService.reissueCode(teacherId(authentication), id));
	}

	@GetMapping("/{id}/students")
	public List<StudentResponse> students(Authentication authentication, @PathVariable Long id) {
		return classroomService.students(teacherId(authentication), id).stream().map(StudentResponse::from).toList();
	}

	@DeleteMapping("/{id}/students/{studentId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void removeStudent(Authentication authentication, @PathVariable Long id, @PathVariable Long studentId) {
		classroomService.removeStudent(teacherId(authentication), id, studentId);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(Authentication authentication, @PathVariable Long id) {
		classroomService.delete(teacherId(authentication), id);
	}

	private ClassroomResponse response(Classroom classroom) {
		return ClassroomResponse.from(classroom, classroomService.studentCount(classroom.getId()));
	}

	private static Long teacherId(Authentication authentication) {
		return Long.valueOf(authentication.getName());
	}
}

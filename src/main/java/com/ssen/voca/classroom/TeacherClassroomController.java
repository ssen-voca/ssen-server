package com.ssen.voca.classroom;

import com.ssen.voca.classroom.dto.ClassroomResponse;
import com.ssen.voca.classroom.dto.CreateClassroomRequest;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
		return ResponseEntity.status(HttpStatus.CREATED).body(ClassroomResponse.from(classroom));
	}

	@GetMapping
	public List<ClassroomResponse> list(Authentication authentication) {
		return classroomService.listOf(teacherId(authentication)).stream().map(ClassroomResponse::from).toList();
	}

	private static Long teacherId(Authentication authentication) {
		return Long.valueOf(authentication.getName());
	}
}

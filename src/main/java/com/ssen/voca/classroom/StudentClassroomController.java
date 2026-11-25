package com.ssen.voca.classroom;

import com.ssen.voca.classroom.dto.JoinClassroomRequest;
import com.ssen.voca.user.dto.ClassroomSummary;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/classroom")
public class StudentClassroomController {

	private final ClassroomJoinService joinService;

	public StudentClassroomController(ClassroomJoinService joinService) {
		this.joinService = joinService;
	}

	@PostMapping
	public ClassroomSummary join(Authentication authentication, @Valid @RequestBody JoinClassroomRequest request) {
		Classroom classroom = joinService.join(Long.valueOf(authentication.getName()), request.code());
		return new ClassroomSummary(classroom.getId(), classroom.getName(), classroom.getCode());
	}

	@DeleteMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void leave(Authentication authentication) {
		joinService.leave(Long.valueOf(authentication.getName()));
	}
}

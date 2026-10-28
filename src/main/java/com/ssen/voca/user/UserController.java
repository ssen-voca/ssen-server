package com.ssen.voca.user;

import com.ssen.voca.auth.InvalidTokenException;
import com.ssen.voca.classroom.ClassroomRepository;
import com.ssen.voca.user.dto.ClassroomSummary;
import com.ssen.voca.user.dto.UserResponse;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

	private final AppUserRepository appUserRepository;
	private final ClassroomRepository classroomRepository;

	public UserController(AppUserRepository appUserRepository, ClassroomRepository classroomRepository) {
		this.appUserRepository = appUserRepository;
		this.classroomRepository = classroomRepository;
	}

	@GetMapping
	public UserResponse me(Authentication authentication) {
		AppUser user = appUserRepository.findById(Long.valueOf(authentication.getName()))
				.orElseThrow(() -> new InvalidTokenException("존재하지 않는 사용자입니다."));
		ClassroomSummary classroom = user.getClassroomId() == null
				? null
				: classroomRepository.findById(user.getClassroomId())
						.map(found -> new ClassroomSummary(found.getId(), found.getName(), found.getCode()))
						.orElse(null);
		return new UserResponse(user.getId(), user.getName(), user.getRole(), user.getEmail(), classroom);
	}
}

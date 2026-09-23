package com.ssen.voca.user;

import com.ssen.voca.user.dto.ClassCodeRequest;
import com.ssen.voca.user.dto.UserResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

	private final AppUserRepository appUserRepository;

	public UserController(AppUserRepository appUserRepository) {
		this.appUserRepository = appUserRepository;
	}

	@GetMapping
	public UserResponse me(Authentication authentication) {
		AppUser user = findCurrentUser(authentication);
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getClassCode(), user.getRole());
	}

	@PatchMapping("/class-code")
	public UserResponse updateClassCode(Authentication authentication, @Valid @RequestBody ClassCodeRequest request) {
		AppUser user = findCurrentUser(authentication);
		user.updateClassCode(request.classCode());
		appUserRepository.save(user);
		return new UserResponse(user.getId(), user.getName(), user.getEmail(), user.getClassCode(), user.getRole());
	}

	private AppUser findCurrentUser(Authentication authentication) {
		Long userId = Long.valueOf(authentication.getName());
		return appUserRepository.findById(userId).orElseThrow();
	}
}

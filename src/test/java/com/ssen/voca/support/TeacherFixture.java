package com.ssen.voca.support;

import com.ssen.voca.auth.JwtService;
import com.ssen.voca.auth.Names;
import com.ssen.voca.auth.Schools;
import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 테스트용 교사 계정을 만든다. 학교는 매번 새 이름이라 시도 제한 키가 겹치지 않는다. */
@Component
public class TeacherFixture {

	public record Teacher(Long id, String school, String name, String pin, String token, String refreshToken) {
	}

	@Autowired
	private AppUserRepository appUserRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private JwtService jwtService;

	public Teacher create() {
		return create("픽스처교사");
	}

	public Teacher create(String name) {
		String school = "픽스처학원-" + UUID.randomUUID();
		String pin = "4321";
		AppUser teacher = appUserRepository.saveAndFlush(AppUser.teacher(
				name, Names.key(name), passwordEncoder.encode(pin), school, Schools.key(school)));
		return new Teacher(teacher.getId(), school, name, pin,
				jwtService.generateAccessToken(teacher.getId(), name, AppUser.TEACHER),
				jwtService.generateRefreshToken(teacher.getId()));
	}
}

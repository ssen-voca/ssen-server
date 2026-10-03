package com.ssen.voca.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeacherSignupRequest(
		@NotBlank(message = "이름을 입력해 주세요")
		@Size(max = 50, message = "이름은 50자 이하로 입력해 주세요")
		String name,
		@NotBlank(message = "이메일을 입력해 주세요")
		@Email(message = "올바른 이메일 형식이 아니에요")
		@Size(max = 255, message = "이메일은 255자 이하로 입력해 주세요")
		String email,
		@NotBlank(message = "비밀번호를 입력해 주세요")
		@Size(min = 8, message = "비밀번호는 8자 이상으로 입력해 주세요")
		String password,
		@NotBlank(message = "교사 가입 코드를 입력해 주세요")
		String inviteCode) {

	// 앞뒤 공백을 제거한 값으로 검증·저장한다 (비밀번호는 그대로 둔다).
	public TeacherSignupRequest {
		email = email == null ? null : email.strip();
		inviteCode = inviteCode == null ? null : inviteCode.strip();
	}
}

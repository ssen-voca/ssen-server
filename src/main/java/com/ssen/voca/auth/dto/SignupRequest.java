package com.ssen.voca.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
		@NotBlank(message = "이름을 입력해 주세요")
		@Size(max = 50, message = "이름은 50자 이하로 입력해 주세요")
		String name,
		@NotNull(message = "휴대폰 번호 뒤 4자리를 숫자로 입력해 주세요")
		@Pattern(regexp = "\\d{4}", message = "휴대폰 번호 뒤 4자리를 숫자로 입력해 주세요")
		String phoneLast4) {
}

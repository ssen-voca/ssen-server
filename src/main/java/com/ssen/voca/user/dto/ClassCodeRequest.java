package com.ssen.voca.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ClassCodeRequest(
		@NotBlank(message = "참여 코드를 입력해 주세요")
		@Size(max = 50, message = "참여 코드는 50자 이하로 입력해 주세요")
		String classCode) {

	// 앞뒤 공백을 제거한 값으로 검증·저장한다 (공백뿐인 코드는 빈 문자열이 되어 @NotBlank에 걸린다).
	public ClassCodeRequest {
		classCode = classCode == null ? null : classCode.strip();
	}
}

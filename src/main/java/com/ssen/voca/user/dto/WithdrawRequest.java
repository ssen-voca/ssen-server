package com.ssen.voca.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record WithdrawRequest(
		@NotNull(message = "휴대폰 번호 뒤 4자리를 숫자로 입력해 주세요")
		@Pattern(regexp = "\\d{4}", message = "휴대폰 번호 뒤 4자리를 숫자로 입력해 주세요")
		String phoneLast4) {
}

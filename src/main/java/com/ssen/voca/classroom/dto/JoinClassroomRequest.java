package com.ssen.voca.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JoinClassroomRequest(
		@NotBlank(message = "참여 코드를 입력해 주세요")
		@Size(max = 20, message = "참여 코드를 확인해 주세요.")
		String code) {
}

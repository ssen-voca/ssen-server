package com.ssen.voca.classroom.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateClassroomRequest(
		@NotBlank(message = "수업 이름을 입력해 주세요")
		@Size(max = 100, message = "수업 이름은 100자 이하로 입력해 주세요")
		String name) {

	// 앞뒤 공백(전각 공백 포함)을 제거한 값으로 검증·저장한다.
	public CreateClassroomRequest {
		name = name == null ? null : name.strip();
	}
}

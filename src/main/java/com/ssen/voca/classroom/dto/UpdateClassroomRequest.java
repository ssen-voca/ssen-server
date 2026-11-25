package com.ssen.voca.classroom.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 둘 다 선택이지만 하나는 있어야 한다(컨트롤러가 확인). name은 있으면 생성과 같은 규칙을 따른다. */
public record UpdateClassroomRequest(
		@Pattern(regexp = "(?s).+", message = "수업 이름을 입력해 주세요")
		@Size(max = 100, message = "수업 이름은 100자 이하로 입력해 주세요")
		String name,
		Boolean joinable) {

	public UpdateClassroomRequest {
		name = name == null ? null : name.strip();
	}
}

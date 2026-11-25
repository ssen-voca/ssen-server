package com.ssen.voca.user.dto;

/** position: 교사 계정이면 TEACHER 또는 ASSISTANT(조교, 권한은 같고 표시만 다름), 학생이면 null. */
public record UserResponse(
		Long id, String name, String role, String email, String school, ClassroomSummary classroom, String position) {
}

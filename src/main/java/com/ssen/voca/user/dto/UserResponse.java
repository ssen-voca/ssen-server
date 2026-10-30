package com.ssen.voca.user.dto;

public record UserResponse(Long id, String name, String role, String email, String school, ClassroomSummary classroom) {
}

package com.ssen.voca.classroom.dto;

import com.ssen.voca.classroom.Classroom;
import java.time.LocalDateTime;

public record ClassroomResponse(Long id, String name, String code, LocalDateTime createdAt) {

	public static ClassroomResponse from(Classroom classroom) {
		return new ClassroomResponse(
				classroom.getId(), classroom.getName(), classroom.getCode(), classroom.getCreatedAt());
	}
}

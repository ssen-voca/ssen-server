package com.ssen.voca.classroom.dto;

import com.ssen.voca.user.AppUser;

/** 교사에게 보여 주는 학생 정보. 번호 해시 등 내부 값은 담지 않는다. */
public record StudentResponse(Long id, String name, String school) {

	public static StudentResponse from(AppUser student) {
		return new StudentResponse(student.getId(), student.getName(), student.getSchool());
	}
}

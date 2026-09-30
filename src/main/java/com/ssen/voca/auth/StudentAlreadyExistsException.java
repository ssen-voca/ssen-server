package com.ssen.voca.auth;

public class StudentAlreadyExistsException extends RuntimeException {

	public StudentAlreadyExistsException() {
		super("이미 가입된 학생이에요. 로그인해 주세요.");
	}
}

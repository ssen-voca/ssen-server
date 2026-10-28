package com.ssen.voca.auth;

public class TeacherEmailExistsException extends RuntimeException {

	public TeacherEmailExistsException() {
		super("이미 가입된 이메일이에요. 로그인해 주세요.");
	}
}

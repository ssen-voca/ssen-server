package com.ssen.voca.auth;

public class EmailAlreadyExistsException extends RuntimeException {

	public EmailAlreadyExistsException(String email) {
		super("이미 가입된 이메일입니다: " + email);
	}
}

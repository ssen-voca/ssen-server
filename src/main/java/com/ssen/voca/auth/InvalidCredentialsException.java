package com.ssen.voca.auth;

public class InvalidCredentialsException extends RuntimeException {

	public InvalidCredentialsException() {
		super("이름 또는 휴대폰 번호가 올바르지 않아요.");
	}

	public InvalidCredentialsException(String message) {
		super(message);
	}
}

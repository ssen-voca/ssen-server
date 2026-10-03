package com.ssen.voca.auth;

public class InvalidInviteCodeException extends RuntimeException {

	public InvalidInviteCodeException() {
		super("교사 가입 코드가 올바르지 않아요.");
	}
}

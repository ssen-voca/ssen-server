package com.ssen.voca.auth;

/** 비밀번호가 BCrypt 한도(UTF-8 72바이트)를 넘을 때. 조용히 잘리지 않도록 가입에서 거절한다. */
public class InvalidPasswordException extends RuntimeException {

	public InvalidPasswordException() {
		super("비밀번호가 너무 길어요. 한글 24자, 영문·숫자 72자 이내로 입력해 주세요.");
	}
}

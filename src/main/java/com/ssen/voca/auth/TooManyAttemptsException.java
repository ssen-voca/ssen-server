package com.ssen.voca.auth;

public class TooManyAttemptsException extends RuntimeException {

	public TooManyAttemptsException() {
		super("시도 횟수를 초과했어요. 5분 뒤에 다시 시도해 주세요.");
	}
}

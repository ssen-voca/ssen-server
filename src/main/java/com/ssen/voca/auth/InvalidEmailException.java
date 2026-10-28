package com.ssen.voca.auth;

/** 정규화한 이메일이 VARCHAR(255)를 넘을 때. Bean Validation과 같은 메시지로 400을 내려준다. */
public class InvalidEmailException extends RuntimeException {

	public static final String TOO_LONG = "이메일은 255자 이하로 입력해 주세요";

	public InvalidEmailException() {
		super(TOO_LONG);
	}
}

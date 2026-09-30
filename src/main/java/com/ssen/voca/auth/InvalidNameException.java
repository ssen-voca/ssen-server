package com.ssen.voca.auth;

/** 정규화한 이름이 비었거나 너무 길 때. Bean Validation과 같은 메시지로 400을 내려준다. */
public class InvalidNameException extends RuntimeException {

	public static final String BLANK = "이름을 입력해 주세요";
	public static final String TOO_LONG = "이름은 50자 이하로 입력해 주세요";

	public InvalidNameException(String message) {
		super(message);
	}
}

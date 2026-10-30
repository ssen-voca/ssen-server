package com.ssen.voca.auth;

/** 정규화한 학교가 비었거나 너무 길 때. Bean Validation과 같은 메시지로 400을 내려준다. */
public class InvalidSchoolException extends RuntimeException {

	public static final String BLANK = "학교를 입력해 주세요";
	public static final String TOO_LONG = "학교 이름은 100자 이하로 입력해 주세요";

	public InvalidSchoolException(String message) {
		super(message);
	}
}

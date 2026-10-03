package com.ssen.voca.classroom;

import java.util.Locale;
import java.util.regex.Pattern;

/** 참여코드의 글자 집합과 입력 정규화. 0/O, 1/I/L처럼 헷갈리는 글자는 코드에 쓰지 않는다. */
public final class ClassCodes {

	public static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
	public static final int LENGTH = 6;

	private static final Pattern FORMAT = Pattern.compile("[" + ALPHABET + "]{" + LENGTH + "}");

	private ClassCodes() {
	}

	/** 앞뒤 공백을 제거하고 대문자로 바꾼다. 코드 형식이 아니면 null (DB를 조회할 필요가 없는 입력). */
	public static String normalize(String raw) {
		String code = raw.strip().toUpperCase(Locale.ROOT);
		return FORMAT.matcher(code).matches() ? code : null;
	}
}

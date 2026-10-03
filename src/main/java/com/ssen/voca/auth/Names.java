package com.ssen.voca.auth;

import java.util.Locale;
import java.util.regex.Pattern;

/** 이름의 표시용·식별용 정규화. 학생과 교사가 같은 규칙을 쓴다. */
final class Names {

	// 폭 없는 문자는 눈에 보이지 않으므로 이름 정규화에서 제거한다.
	private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]");
	private static final int MAX_LENGTH = 50;

	private Names() {
	}

	static String display(String name) {
		return INVISIBLE.matcher(name).replaceAll("").strip();
	}

	/** 정규화한 이름 키. 비었거나 50자를 넘으면(소문자화로 길어질 수 있다) 제한 카운터·DB에 닿기 전에 거절한다. */
	static String key(String name) {
		String key = display(name).replaceAll("(?U)\\s+", " ").toLowerCase(Locale.ROOT);
		if (key.isBlank()) {
			throw new InvalidNameException(InvalidNameException.BLANK);
		}
		if (key.length() > MAX_LENGTH) {
			throw new InvalidNameException(InvalidNameException.TOO_LONG);
		}
		return key;
	}
}

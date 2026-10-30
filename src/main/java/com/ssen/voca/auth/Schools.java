package com.ssen.voca.auth;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/** 학교의 표시용·식별용 정규화. 표기 차이(공백·대소문자·한글 조합 방식)를 같은 학교로 본다. */
final class Schools {

	private static final Pattern INVISIBLE = Pattern.compile("[\\u200B\\u200C\\u200D\\u2060\\uFEFF]");
	private static final int MAX_LENGTH = 100;

	private Schools() {
	}

	private static String clean(String school) {
		return Normalizer.normalize(INVISIBLE.matcher(school).replaceAll(""), Normalizer.Form.NFC);
	}

	static String display(String school) {
		return clean(school).replaceAll("(?U)\\s+", " ").strip();
	}

	/** 정규화한 학교 키. 모든 공백을 지운다. 비었거나 100자를 넘으면 제한 카운터·DB에 닿기 전에 거절한다. */
	static String key(String school) {
		String key = clean(school).replaceAll("(?U)\\s+", "").toLowerCase(Locale.ROOT);
		if (key.isEmpty()) {
			throw new InvalidSchoolException(InvalidSchoolException.BLANK);
		}
		if (key.length() > MAX_LENGTH) {
			throw new InvalidSchoolException(InvalidSchoolException.TOO_LONG);
		}
		return key;
	}
}

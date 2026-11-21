package com.ssen.voca.auth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 서버 설정 {@code app.teacher.accounts}(환경 변수 TEACHER_ACCOUNTS)의 파서.
 * 항목은 {@code ;} 또는 줄바꿈으로 나누고, 각 항목은 {@code 학교|이름|번호4자리}다.
 * 오류 메시지에는 항목 번호(1부터)만 담고 번호(PIN)는 절대 싣지 않는다.
 */
final class TeacherAccounts {

	private static final Pattern ENTRY_SEPARATOR = Pattern.compile("[;\\r\\n]+");
	private static final Pattern PIN = Pattern.compile("[0-9]{4}");

	/** toString에서 PIN을 숨겨 로그에 남지 않게 한다. */
	record Entry(String school, String name, String pin) {

		@Override
		public String toString() {
			return "Entry[school=" + school + ", name=" + name + ", pin=****]";
		}
	}

	private TeacherAccounts() {
	}

	static List<Entry> parse(String raw) {
		List<Entry> entries = new ArrayList<>();
		if (raw == null) {
			return entries;
		}
		Map<String, Integer> seen = new HashMap<>();
		int number = 0;
		for (String text : ENTRY_SEPARATOR.split(raw)) {
			if (text.isBlank()) {
				continue;
			}
			number++;
			Entry entry = parseEntry(number, text);
			String identity = Schools.key(entry.school()) + "\u0000" + Names.key(entry.name());
			Integer first = seen.putIfAbsent(identity, number);
			if (first != null) {
				throw invalid(number, "학교와 이름이 " + first + "번 항목과 같아요");
			}
			entries.add(entry);
		}
		return entries;
	}

	private static Entry parseEntry(int number, String text) {
		String[] fields = text.split("\\|", -1);
		if (fields.length != 3) {
			throw invalid(number, "'학교|이름|번호4자리' 형식이어야 해요");
		}
		String school = fields[0].strip();
		String name = fields[1].strip();
		String pin = fields[2].strip();
		try {
			Schools.key(school);
			Names.key(name);
		} catch (InvalidSchoolException | InvalidNameException e) {
			throw invalid(number, e.getMessage());
		}
		if (!PIN.matcher(pin).matches()) {
			throw invalid(number, "번호는 숫자 4자리여야 해요");
		}
		return new Entry(school, name, pin);
	}

	private static IllegalStateException invalid(int number, String problem) {
		return new IllegalStateException("app.teacher.accounts " + number + "번 항목이 올바르지 않아요: " + problem);
	}
}

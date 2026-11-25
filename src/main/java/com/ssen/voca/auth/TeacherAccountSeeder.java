package com.ssen.voca.auth;

import com.ssen.voca.user.AppUser;
import com.ssen.voca.user.AppUserRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 서버 설정({@code app.teacher.accounts})의 교사 계정을 시작할 때 DB에 맞춘다. 설정이 기준이라 번호를 바꾸면 해시가 바뀐다.
 * 설정에서 뺀 교사는 지우지 않고 비활성으로 표시해 로그인·갱신·이미 받은 토큰을 막고, 다시 넣으면 활성으로 되돌린다.
 * 설정이 비어 있으면 환경 변수를 실수로 비운 경우일 수 있어 아무도 비활성화하지 않는다.
 */
@Component
public class TeacherAccountSeeder implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(TeacherAccountSeeder.class);

	private final AppUserRepository appUserRepository;
	private final PasswordEncoder passwordEncoder;
	private final List<TeacherAccounts.Entry> entries;

	public TeacherAccountSeeder(
			AppUserRepository appUserRepository,
			PasswordEncoder passwordEncoder,
			@Value("${app.teacher.accounts:}") String accounts) {
		this.appUserRepository = appUserRepository;
		this.passwordEncoder = passwordEncoder;
		// 서버가 뜨기 전에 형식 오류로 실패시킨다.
		this.entries = TeacherAccounts.parse(accounts);
	}

	@Override
	@Transactional
	public void run(ApplicationArguments args) {
		seed(entries);
	}

	@Transactional
	void seed(List<TeacherAccounts.Entry> entries) {
		int created = 0;
		int rotated = 0;
		int reenabled = 0;
		int number = 0;
		Set<String> configured = new HashSet<>();
		for (TeacherAccounts.Entry entry : entries) {
			number++;
			String schoolKey = Schools.key(entry.school());
			String nameKey = Names.key(entry.name());
			configured.add(identity(schoolKey, nameKey));
			// 같은 (학교, 이름, 번호)의 학생이 있으면 로그인 때 그 학생이 교사가 되어 버리므로 시작을 막는다.
			boolean studentCollides = appUserRepository
					.findAllBySchoolKeyAndNameKeyAndRole(schoolKey, nameKey, AppUser.STUDENT).stream()
					.anyMatch(user -> passwordEncoder.matches(entry.pin(), user.getSecretHash()));
			if (studentCollides) {
				throw new IllegalStateException("app.teacher.accounts " + number
						+ "번 항목의 학교·이름·번호로 이미 학생이 가입돼 있어요. 이 항목의 번호를 바꿔 주세요");
			}
			List<AppUser> existing = appUserRepository.findAllBySchoolKeyAndNameKeyAndRole(
					schoolKey, nameKey, AppUser.TEACHER);
			if (existing.isEmpty()) {
				appUserRepository.save(AppUser.teacher(
						Names.display(entry.name()),
						nameKey,
						passwordEncoder.encode(entry.pin()),
						Schools.display(entry.school()),
						schoolKey));
				created++;
			} else {
				for (AppUser user : existing) {
					if (user.isDisabled()) {
						user.enable();
						reenabled++;
					}
					if (!passwordEncoder.matches(entry.pin(), user.getSecretHash())) {
						user.changeSecretHash(passwordEncoder.encode(entry.pin()));
						rotated++;
					}
				}
			}
		}
		if (entries.isEmpty()) {
			log.warn("TEACHER_ACCOUNTS가 비어 있어 기존 교사 계정을 그대로 둬요");
			return;
		}
		int disabled = 0;
		for (AppUser teacher : appUserRepository.findAllByRole(AppUser.TEACHER)) {
			boolean listed = teacher.getSchoolKey() != null
					&& configured.contains(identity(teacher.getSchoolKey(), teacher.getNameKey()));
			if (!listed && !teacher.isDisabled()) {
				teacher.disable();
				disabled++;
			}
		}
		log.info("교사 계정 {}개 확인: 새로 만든 {}개, 번호를 바꾼 {}개, 다시 켠 {}개, 설정에서 빠져 끈 {}개",
				entries.size(), created, rotated, reenabled, disabled);
	}

	private static String identity(String schoolKey, String nameKey) {
		return schoolKey + "\u0000" + nameKey;
	}
}

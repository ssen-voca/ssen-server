package com.ssen.voca.auth;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 이름 키별 시도 횟수 제한 (선차감 + 원자적). 창은 첫 시도 시각에 열리고, windowMillis가 지나면 초기화된다.
 * {@link #tryAcquire}가 한도 확인과 차감을 한 번에 하므로 병렬 요청도 창당 maxAttempts회를 넘지 못한다.
 * 로그인 성공은 {@link #release}로 자기 몫 1회만 돌려받는다 (전체를 비우면 같은 이름의 자기 계정으로 로그인해 카운터를 리셋할 수 있다).
 */
// ponytail: single-instance in-memory; move to Redis/DB if the server ever runs more than one instance
@Component
public class LoginAttemptLimiter {

	private static final int SWEEP_THRESHOLD = 10_000;

	private record Window(long startMillis, int count) {
	}

	private final Clock clock;
	private final int maxAttempts;
	private final long windowMillis;
	private final Map<String, Window> windows = new HashMap<>();

	public LoginAttemptLimiter(
			Clock clock,
			@Value("${app.auth.max-attempts:5}") int maxAttempts,
			@Value("${app.auth.attempt-window-millis:300000}") long windowMillis) {
		this.clock = clock;
		this.maxAttempts = maxAttempts;
		this.windowMillis = windowMillis;
	}

	/** 창에 이미 maxAttempts회가 있으면 TooManyAttemptsException (거절된 시도는 세지 않는다). 아니면 1회 차감. */
	public synchronized void tryAcquire(String key) {
		long now = clock.millis();
		Window window = windows.get(key);
		if (window == null || isExpired(window, now)) {
			windows.put(key, new Window(now, 1));
		} else if (window.count() >= maxAttempts) {
			throw new TooManyAttemptsException();
		} else {
			windows.put(key, new Window(window.startMillis(), window.count() + 1));
		}
		if (windows.size() > SWEEP_THRESHOLD) {
			windows.values().removeIf(w -> isExpired(w, now));
		}
	}

	/** 로그인 성공 시 자기 차감 1회를 돌려준다. 0 아래로는 내려가지 않는다. */
	public synchronized void release(String key) {
		Window window = windows.get(key);
		if (window == null) {
			return;
		}
		if (isExpired(window, clock.millis()) || window.count() <= 1) {
			windows.remove(key);
		} else {
			windows.put(key, new Window(window.startMillis(), window.count() - 1));
		}
	}

	private boolean isExpired(Window window, long now) {
		return now - window.startMillis() >= windowMillis;
	}
}

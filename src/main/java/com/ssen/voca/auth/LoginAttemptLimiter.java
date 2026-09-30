package com.ssen.voca.auth;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 이름 키별 시도 횟수 제한. 창은 첫 시도 시각에 열리고, windowMillis가 지나면 초기화된다.
 * 횟수가 maxAttempts에 도달하면 다음 시도는 거절된다.
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

	/** 한도에 도달했으면 TooManyAttemptsException. 만료된 창은 이 자리에서 버린다. */
	public synchronized void checkAllowed(String key) {
		Window window = windows.get(key);
		if (window == null) {
			return;
		}
		if (isExpired(window, clock.millis())) {
			windows.remove(key);
			return;
		}
		if (window.count() >= maxAttempts) {
			throw new TooManyAttemptsException();
		}
	}

	public synchronized void recordAttempt(String key) {
		long now = clock.millis();
		Window window = windows.get(key);
		windows.put(key, window == null || isExpired(window, now)
				? new Window(now, 1)
				: new Window(window.startMillis(), window.count() + 1));
		if (windows.size() > SWEEP_THRESHOLD) {
			windows.values().removeIf(w -> isExpired(w, now));
		}
	}

	public synchronized void clear(String key) {
		windows.remove(key);
	}

	private boolean isExpired(Window window, long now) {
		return now - window.startMillis() >= windowMillis;
	}
}

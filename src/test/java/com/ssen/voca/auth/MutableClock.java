package com.ssen.voca.auth;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** 테스트에서 sleep 없이 시간을 흘리기 위한 시계. */
class MutableClock extends Clock {

	private Instant now = Instant.parse("2026-01-01T00:00:00Z");

	void advanceMillis(long millis) {
		now = now.plusMillis(millis);
	}

	@Override
	public ZoneId getZone() {
		return ZoneOffset.UTC;
	}

	@Override
	public Clock withZone(ZoneId zone) {
		return this;
	}

	@Override
	public Instant instant() {
		return now;
	}
}

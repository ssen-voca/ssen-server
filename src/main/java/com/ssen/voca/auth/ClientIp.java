package com.ssen.voca.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 시도 제한용 접속 IP. 설정({@code app.auth.client-ip-header})이 비어 있으면 연결 주소를 쓴다.
 * 헤더를 지정하면 그 헤더의 마지막 값을 쓴다: 프록시는 자기가 본 주소를 맨 뒤에 붙이므로
 * 클라이언트가 앞쪽에 넣은 위조 값은 무시된다. 헤더가 없거나 비어 있으면 연결 주소로 대체한다.
 */
@Component
public class ClientIp {

	private static final int MAX_LENGTH = 100;

	private final String header;

	public ClientIp(@Value("${app.auth.client-ip-header:}") String header) {
		this.header = header == null ? "" : header.strip();
	}

	public String of(HttpServletRequest request) {
		String ip = null;
		if (!header.isEmpty()) {
			String value = request.getHeader(header);
			if (value != null) {
				String last = value.substring(value.lastIndexOf(',') + 1).strip();
				if (!last.isEmpty()) {
					ip = last;
				}
			}
		}
		if (ip == null) {
			ip = request.getRemoteAddr();
		}
		if (ip == null) {
			ip = "";
		}
		return ip.length() > MAX_LENGTH ? ip.substring(0, MAX_LENGTH) : ip;
	}
}

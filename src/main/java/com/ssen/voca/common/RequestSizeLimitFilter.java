package com.ssen.voca.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * /api 요청 본문의 크기를 제한한다. JSON 본문에는 기본 상한이 없어서 큰 요청으로 서버 메모리를 채울 수 있기 때문이다.
 * Content-Length가 상한을 넘으면 읽기 전에 413으로 거절하고, 길이를 알 수 없는 요청(청크 전송)은 읽는 도중에 끊는다.
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

	static final String MESSAGE = "요청이 너무 커요.";

	/** 상한을 넘게 읽으면 던진다. 컨트롤러 어드바이스가 413으로 바꾼다. */
	public static class TooLargeException extends IOException {
		public TooLargeException() {
			super(MESSAGE);
		}
	}

	private final long maxBytes;

	public RequestSizeLimitFilter(@Value("${app.max-request-bytes:5242880}") long maxBytes) {
		this.maxBytes = maxBytes;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		return !request.getRequestURI().startsWith("/api/");
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (request.getContentLengthLong() > maxBytes) {
			response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
			response.setContentType(MediaType.APPLICATION_JSON_VALUE);
			response.setCharacterEncoding("UTF-8");
			response.getWriter().write("{\"message\":\"" + MESSAGE + "\"}");
			return;
		}
		chain.doFilter(new HttpServletRequestWrapper(request) {
			@Override
			public ServletInputStream getInputStream() throws IOException {
				ServletInputStream original = super.getInputStream();
				return wrap(limit(original, maxBytes), original);
			}
		}, response);
	}

	/** 한 번에 읽은 양을 세어 상한을 넘으면 TooLargeException을 던지는 입력 스트림. */
	static InputStream limit(InputStream in, long maxBytes) {
		return new InputStream() {
			private long read;

			private void count(long n) throws IOException {
				read += n;
				if (read > maxBytes) {
					throw new TooLargeException();
				}
			}

			@Override
			public int read() throws IOException {
				int b = in.read();
				if (b >= 0) {
					count(1);
				}
				return b;
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				int n = in.read(buffer, offset, length);
				if (n > 0) {
					count(n);
				}
				return n;
			}
		};
	}

	private static ServletInputStream wrap(InputStream limited, ServletInputStream original) {
		return new ServletInputStream() {
			@Override
			public int read() throws IOException {
				return limited.read();
			}

			@Override
			public int read(byte[] buffer, int offset, int length) throws IOException {
				return limited.read(buffer, offset, length);
			}

			@Override
			public boolean isFinished() {
				return original.isFinished();
			}

			@Override
			public boolean isReady() {
				return original.isReady();
			}

			@Override
			public void setReadListener(ReadListener listener) {
				original.setReadListener(listener);
			}
		};
	}
}

package com.ssen.voca.auth;

import com.ssen.voca.user.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 이미 발급된 교사 액세스 토큰도 설정에서 빠진(비활성) 교사나 없어진 계정이면 바로 401로 막는다.
 * 교사 토큰일 때만 DB를 한 번 보고, 학생 요청은 DB를 더 조회하지 않는다. JWT 필터 뒤에서 모든 경로에 적용된다.
 */
public class TeacherActiveFilter extends OncePerRequestFilter {

	private final AppUserRepository appUserRepository;

	public TeacherActiveFilter(AppUserRepository appUserRepository) {
		this.appUserRepository = appUserRepository;
	}

	@Override
	protected void doFilterInternal(
			HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getAuthorities().stream()
				.anyMatch(authority -> "ROLE_TEACHER".equals(authority.getAuthority()))
				&& !isActive(authentication.getName())) {
			SecurityContextHolder.clearContext();
			response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
			return;
		}
		filterChain.doFilter(request, response);
	}

	private boolean isActive(String subject) {
		try {
			return appUserRepository.existsByIdAndDisabledFalse(Long.valueOf(subject));
		} catch (NumberFormatException e) {
			return false;
		}
	}
}

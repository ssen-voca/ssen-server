package com.ssen.voca.user;

import com.ssen.voca.user.dto.WithdrawRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/me/withdraw")
public class AccountController {

	private final WithdrawalService withdrawalService;

	public AccountController(WithdrawalService withdrawalService) {
		this.withdrawalService = withdrawalService;
	}

	// DELETE는 본문을 못 싣는 클라이언트·프록시가 있어서 POST로 둔다.
	@PostMapping
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void withdraw(Authentication authentication, @Valid @RequestBody WithdrawRequest request) {
		withdrawalService.withdraw(Long.valueOf(authentication.getName()), request.phoneLast4());
	}
}

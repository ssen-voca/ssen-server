package com.ssen.voca.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "app_user")
@Getter
@NoArgsConstructor
public class AppUser {

	public static final String STUDENT = "STUDENT";
	public static final String TEACHER = "TEACHER";

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(name = "name_key", nullable = false, length = 50)
	private String nameKey;

	// 학생은 휴대폰 뒤 4자리, 교사는 비밀번호의 BCrypt 해시.
	@Column(name = "secret_hash", nullable = false)
	private String secretHash;

	@Column(length = 255)
	private String email;

	@Column(name = "class_code", length = 50)
	private String classCode;

	@Column(nullable = false, length = 20)
	private String role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public AppUser(String name, String nameKey, String secretHash) {
		this.name = name;
		this.nameKey = nameKey;
		this.secretHash = secretHash;
		this.role = STUDENT;
		this.createdAt = LocalDateTime.now();
	}

	public static AppUser teacher(String name, String nameKey, String email, String secretHash) {
		AppUser user = new AppUser(name, nameKey, secretHash);
		user.email = email;
		user.role = TEACHER;
		return user;
	}

	public void updateClassCode(String classCode) {
		this.classCode = classCode;
	}
}

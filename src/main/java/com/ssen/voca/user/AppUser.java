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

	@Column(length = 100)
	private String school;

	@Column(name = "school_key", length = 100)
	private String schoolKey;

	// 학생이 속한 수업 (지금은 모두 null, 배정은 이후 작업. 교사도 null).
	@Column(name = "classroom_id")
	private Long classroomId;

	@Column(nullable = false, length = 20)
	private String role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private AppUser(String name, String nameKey, String secretHash, String school, String schoolKey, String email, String role) {
		this.name = name;
		this.nameKey = nameKey;
		this.secretHash = secretHash;
		this.school = school;
		this.schoolKey = schoolKey;
		this.email = email;
		this.role = role;
		this.createdAt = LocalDateTime.now();
	}

	public AppUser(String name, String nameKey, String secretHash, String school, String schoolKey) {
		this(name, nameKey, secretHash, school, schoolKey, null, STUDENT);
	}

	public static AppUser teacher(String name, String nameKey, String email, String secretHash) {
		return new AppUser(name, nameKey, secretHash, null, null, email, TEACHER);
	}
}

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

	// 학생은 휴대폰 뒤 4자리, 교사는 서버 설정의 번호 4자리를 BCrypt로 해시한 값.
	@Column(name = "secret_hash", nullable = false)
	private String secretHash;

	// 옛 이메일 교사 행에만 값이 있다. 새 계정은 항상 null.
	@Column(length = 255)
	private String email;

	@Column(length = 100)
	private String school;

	@Column(name = "school_key", length = 100)
	private String schoolKey;

	// 학생이 속한 수업. 참여 코드로 들어가기 전에는 null이고 교사도 null.
	@Column(name = "classroom_id")
	private Long classroomId;

	@Column(nullable = false, length = 20)
	private String role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	private AppUser(String name, String nameKey, String secretHash, String school, String schoolKey, String role) {
		this.name = name;
		this.nameKey = nameKey;
		this.secretHash = secretHash;
		this.school = school;
		this.schoolKey = schoolKey;
		this.role = role;
		this.createdAt = LocalDateTime.now();
	}

	public AppUser(String name, String nameKey, String secretHash, String school, String schoolKey) {
		this(name, nameKey, secretHash, school, schoolKey, STUDENT);
	}

	/** 서버 설정으로 등록하는 교사. 학생과 같은 (학교, 이름, 번호)로 로그인한다. */
	public static AppUser teacher(String name, String nameKey, String secretHash, String school, String schoolKey) {
		return new AppUser(name, nameKey, secretHash, school, schoolKey, TEACHER);
	}

	public void joinClassroom(Long classroomId) {
		this.classroomId = classroomId;
	}

	public void changeSecretHash(String secretHash) {
		this.secretHash = secretHash;
	}
}

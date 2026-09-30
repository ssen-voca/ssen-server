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

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 50)
	private String name;

	@Column(name = "name_key", nullable = false, length = 50)
	private String nameKey;

	@Column(name = "pin_hash", nullable = false)
	private String pinHash;

	@Column(name = "class_code", length = 50)
	private String classCode;

	@Column(nullable = false, length = 20)
	private String role;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public AppUser(String name, String nameKey, String pinHash) {
		this.name = name;
		this.nameKey = nameKey;
		this.pinHash = pinHash;
		this.role = "STUDENT";
		this.createdAt = LocalDateTime.now();
	}

	public void updateClassCode(String classCode) {
		this.classCode = classCode;
	}
}

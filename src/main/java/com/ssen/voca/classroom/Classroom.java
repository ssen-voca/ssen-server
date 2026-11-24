package com.ssen.voca.classroom;

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
@Table(name = "classroom")
@Getter
@NoArgsConstructor
public class Classroom {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "teacher_id", nullable = false)
	private Long teacherId;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, length = 6, unique = true)
	private String code;

	// false면 코드로 새로 들어올 수 없다(이미 속한 학생은 제외).
	@Column(nullable = false)
	private boolean joinable = true;

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public Classroom(Long teacherId, String name, String code) {
		this.teacherId = teacherId;
		this.name = name;
		this.code = code;
		this.createdAt = LocalDateTime.now();
	}

	public void rename(String name) {
		this.name = name;
	}

	public void changeCode(String code) {
		this.code = code;
	}

	public void setJoinable(boolean joinable) {
		this.joinable = joinable;
	}
}

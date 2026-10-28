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

	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	public Classroom(Long teacherId, String name, String code) {
		this.teacherId = teacherId;
		this.name = name;
		this.code = code;
		this.createdAt = LocalDateTime.now();
	}
}

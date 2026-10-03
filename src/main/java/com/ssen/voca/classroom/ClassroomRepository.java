package com.ssen.voca.classroom;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClassroomRepository extends JpaRepository<Classroom, Long> {

	List<Classroom> findAllByTeacherIdOrderByIdDesc(Long teacherId);

	boolean existsByCode(String code);
}

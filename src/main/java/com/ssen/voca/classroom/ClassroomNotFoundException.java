package com.ssen.voca.classroom;

/** 가입할 때 입력한 참여코드에 해당하는 수업이 없을 때. */
public class ClassroomNotFoundException extends RuntimeException {

	public ClassroomNotFoundException() {
		super("참여 코드를 확인해 주세요.");
	}
}

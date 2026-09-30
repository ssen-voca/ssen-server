package com.ssen.voca.user.dto;

import jakarta.validation.constraints.NotBlank;

public record ClassCodeRequest(@NotBlank String classCode) {
}

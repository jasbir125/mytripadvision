package com.mytripadvision.dto;

import java.time.Instant;
import tools.jackson.databind.JsonNode;

public record MessageResponse(String role, String content, JsonNode meta, Instant createdAt) {
}

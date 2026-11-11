package com.mytripadvision.controller;

import com.mytripadvision.dto.HealthResponse;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

/** The web UI at "/", the simple /health check of the Python app, and the empty favicon. */
@Controller
public class HomeController {

    @GetMapping("/")
    public String home() {
        return "forward:/static/index.html";
    }

    @GetMapping("/health")
    @ResponseBody
    public HealthResponse health() {
        return new HealthResponse("ok", "AI Travel Planner API is running");
    }

    @GetMapping("/favicon.ico")
    public ResponseEntity<Map<String, Object>> favicon() {
        return ResponseEntity.ok(Map.of());
    }
}

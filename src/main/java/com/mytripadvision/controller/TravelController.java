package com.mytripadvision.controller;

import com.mytripadvision.dto.ReviewRequest;
import com.mytripadvision.dto.TravelRequest;
import com.mytripadvision.dto.TravelResponse;
import com.mytripadvision.service.TravelService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Paths stay under /api (not /api/v1) because the bundled web UI calls them. */
@RestController
@RequestMapping("/api/travel")
@RequiredArgsConstructor
public class TravelController {

    private final TravelService travel;

    @PostMapping
    public TravelResponse plan(@Valid @RequestBody TravelRequest request) {
        return travel.plan(request);
    }

    /** The user's answer to a plan that waits for review: approve it or ask for changes. */
    @PostMapping("/review")
    public TravelResponse review(@Valid @RequestBody ReviewRequest request) {
        return travel.review(request);
    }
}

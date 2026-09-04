package com.schwab.urlshortener.url;

import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/urls")
public class UrlApiController {

    private final UrlService urlService;
    private final String baseUrl;

    public UrlApiController(UrlService urlService, @Value("${app.base-url}") String baseUrl) {
        this.urlService = urlService;
        this.baseUrl = baseUrl;
    }

    @PostMapping
    public ResponseEntity<UrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
        Url url = urlService.create(request.longUrl());
        return ResponseEntity.status(HttpStatus.CREATED).body(UrlResponse.from(url, baseUrl));
    }

    @GetMapping("/{shortCode}")
    public UrlResponse lookup(@PathVariable String shortCode) {
        Url url = urlService.findByShortCode(shortCode);
        return UrlResponse.from(url, baseUrl);
    }
}

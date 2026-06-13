package com.globaltalenthub.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class ConfigController {

    @Value("${MAPBOX_ACCESS_TOKEN:}")
    private String mapboxToken;

    @GetMapping("/api/config")
    public Map<String, String> config() {
        return Map.of("mapboxToken", mapboxToken);
    }
}

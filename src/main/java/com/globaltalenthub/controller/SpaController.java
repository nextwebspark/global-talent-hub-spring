package com.globaltalenthub.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback — non-/api/* paths serve index.html for client-side routing.
 */
@Controller
public class SpaController {

    // Extension-less, non-/api paths forward to the SPA shell. Depth-limited
    // explicit patterns because Spring Boot 3's PathPattern parser forbids a
    // capture segment after "**" (e.g. the old "/**/{path:[^\\.]*}").
    @GetMapping(value = {
        "/{p0:[^\\.]*}",
        "/{p0:[^\\.]*}/{p1:[^\\.]*}",
        "/{p0:[^\\.]*}/{p1:[^\\.]*}/{p2:[^\\.]*}",
        "/{p0:[^\\.]*}/{p1:[^\\.]*}/{p2:[^\\.]*}/{p3:[^\\.]*}"
    })
    public String spa() {
        return "forward:/index.html";
    }
}

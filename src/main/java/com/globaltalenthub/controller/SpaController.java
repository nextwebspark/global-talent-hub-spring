package com.globaltalenthub.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA fallback — non-/api/* paths serve index.html for client-side routing.
 */
@Controller
public class SpaController {

    @GetMapping(value = {"/{path:[^\\.]*}", "/**/{path:[^\\.]*}"})
    public String spa() {
        return "forward:/index.html";
    }
}

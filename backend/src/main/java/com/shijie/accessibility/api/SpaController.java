package com.shijie.accessibility.api;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class SpaController {
    @GetMapping(value = {
            "/{segment:[^\\.]+}",
            "/{segment1:[^\\.]+}/{segment2:[^\\.]+}",
            "/{segment1:[^\\.]+}/{segment2:[^\\.]+}/{segment3:[^\\.]+}"
    })
    public String forwardClientRoutes() {
        return "forward:/index.html";
    }
}


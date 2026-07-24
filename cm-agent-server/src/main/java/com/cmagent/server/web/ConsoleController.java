package com.cmagent.server.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;

@Controller
/** 提供轻量控制台静态页面入口，不承载业务数据和权限逻辑。 */
class ConsoleController {

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<Resource> index() {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(new ClassPathResource("META-INF/resources/index.html"));
    }
}

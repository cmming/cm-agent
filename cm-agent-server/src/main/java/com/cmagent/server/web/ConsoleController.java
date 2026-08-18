package com.cmagent.server.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.net.URI;
import java.nio.charset.StandardCharsets;

@Controller
/** 提供轻量控制台静态页面入口，不承载业务数据和权限逻辑。 */
class ConsoleController {

    /**
     * 将未携带版本号的入口导向当前默认版本。
     *
     * <p>使用重定向而不是覆盖根目录中的旧 HTML，确保 v1 可以继续通过固定版本路径访问。</p>
     */
    @GetMapping({"/", "/console", "/console/", "/console/v2", "/console/v2/"})
    ResponseEntity<Void> latest() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/console/v2/login.html"))
                .build();
    }

    /**
     * 返回未改写的 v1 单页控制台资源。
     *
     * <p>该兼容入口刻意继续读取原始 {@code index.html}，后续版本升级不得删除或静默替换它。</p>
     */
    @GetMapping(value = {"/console/v1", "/console/v1/", "/console/v1/index.html"}, produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<Resource> legacyV1() {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(new ClassPathResource("META-INF/resources/index.html"));
    }

}

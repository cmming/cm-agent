package com.cmagent.server.web;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

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
     * 提供独立的 v3 登录入口，不改变已有无版本入口的默认版本。
     *
     * <p>认证与业务请求继续由现有安全链处理；此处仅重定向到同源静态页面。</p>
     */
    @GetMapping({"/console/v3", "/console/v3/"})
    ResponseEntity<Void> workspaceV3() {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/console/v3/login.html"))
                .build();
    }

    /**
     * 将 v3 已知短路径映射到对应的静态页面。
     *
     * <p>路由正则只接收固定页面名，不能根据任意客户端路径构造重定向，
     * 以免将此兼容入口扩展为开放重定向或静态资源代理。</p>
     * <p>显式声明路径变量名称，使未保留 Java 反射参数名的构建也能正确绑定。</p>
     *
     * @param page 已由路由白名单限定的页面名称
     * @return 指向同源 v3 HTML 的临时重定向
     */
    @GetMapping("/console/v3/{page:login|overview|agents|model-configs|tools|skills|projects|local-client|chat|runs|audit}")
    ResponseEntity<Void> workspaceV3Page(@PathVariable("page") String page) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create("/console/v3/" + page + ".html"))
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

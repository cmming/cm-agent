package com.cmagent.server.web;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ConsoleControllerRoutingTest {

    private final MockMvc mockMvc = standaloneSetup(new ConsoleController()).build();

    @Test
    /**
     * 验证无版本入口稳定跳转到当前默认的 v2 登录页。
     */
    void 默认入口跳转到v2登录页() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/console/v2/login.html"));

        mockMvc.perform(get("/console/v2/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/console/v2/login.html"));
    }

    @Test
    void v3独立入口与固定短路径不改变默认版本() throws Exception {
        for (String entry : new String[]{"/console/v3", "/console/v3/"}) {
            mockMvc.perform(get(entry))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/console/v3/login.html"));
        }
        for (String page : new String[]{"login", "overview", "agents", "model-configs", "tools", "skills",
                "projects", "local-client", "chat", "runs", "audit"}) {
            mockMvc.perform(get("/console/v3/" + page))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "/console/v3/" + page + ".html"));
        }
        mockMvc.perform(get("/console/v3/unsupported"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/console/v3/https:external.example"))
                .andExpect(status().isNotFound());
    }

    @Test
    /**
     * 验证 v1 兼容路径继续返回原始单页控制台。
     */
    void v1兼容入口返回原始页面() throws Exception {
        mockMvc.perform(get("/console/v1/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andExpect(content().string(containsString("CM Agent 控制台")))
                .andExpect(content().string(containsString("id=\"overviewPage\"")));
    }

}

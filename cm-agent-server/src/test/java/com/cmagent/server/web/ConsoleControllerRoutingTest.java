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

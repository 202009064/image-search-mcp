package com.yupi.yuimagesearchmap.tools;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

// 启动需要 Pexels API Key（PexelsProperties 会校验）。
// 想真实调用 Pexels 接口验证，先设置环境变量 PEXELS_API_KEY，或在 IDE Run Configuration 里配；
// 没配时用兜底值 test-key，接口会返回鉴权失败，仅保证上下文能正常启动
@SpringBootTest(properties = "pexels.api-key=${PEXELS_API_KEY:test-key}")
class ImageSearchToolTest {

    @Resource
    private ImageSearchTool imageSearchTool;

    @Test
    void searchImage() {
        // 传空的 ToolContext：走服务端配置里的 Key（模拟 stdio 模式 / 客户端未带 ?key=）
        String result = imageSearchTool.searchImage("computer", 5, new ToolContext(Map.of()));
        System.out.println(result);
        Assertions.assertNotNull(result);
    }
}

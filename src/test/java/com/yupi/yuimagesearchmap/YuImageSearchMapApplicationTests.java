package com.yupi.yuimagesearchmap;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

// 启动需要 Pexels API Key（PexelsProperties 会校验），测试环境给个兜底值，
// 若本机/IDE 已设置 PEXELS_API_KEY 环境变量，则用真实值
@SpringBootTest(properties = "pexels.api-key=${PEXELS_API_KEY:test-key}")
class YuImageSearchMapApplicationTests {

    @Test
    void contextLoads() {
    }

}

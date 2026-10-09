package com.yupi.yuimagesearchmap.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Pexels 图片搜索配置，把写死在代码里的 API Key 抽成外部可配置属性。
 *
 * 取值优先级：客户端查询参数 ?key= ＞ 环境变量 PEXELS_API_KEY ＞ application.yaml 里的
 * pexels.api-key
 * 
 *
 * 环境变量怎么传：
 * - stdio 模式：MCP 客户端配置里的 env 字段
 * - 网络模式：系统环境变量，或容器 -e 参数
 * - 本地调试：IDE Run Configuration 的 Environment variables
 *
 * 注意：PEXELS_API_KEY 是「服务端 → Pexels」的密钥，
 * 与「客户端 → 服务端」的鉴权 token 是两回事。
 */
@Slf4j
@Data
@Component // 会把 PexelsProperties 类注册到 Spring 容器里，成为一个 Bean，后续通过 @Autowired 或构造器注入来使用它
@ConfigurationProperties(prefix = "pexels") // 从 application.yaml 里的 pexels 配置里读取相关配置项
public class PexelsProperties {

    /**
     * Pexels 的 API Key，实际值来自环境变量 PEXELS_API_KEY
     */
    private String apiKey;

    /**
     * Pexels 图片搜索接口地址
     */
    private String apiUrl;

    /**
     * 默认返回的图片数量。
     */
    private int defaultCount = 5;

    /**
     * 启动时校验配置。
     *
     * apiUrl 缺失属于配置错误，直接启动失败。
     * apiKey 缺失只告警、不阻断：网络模式下 Key 允许由客户端通过查询参数自带，
     * 服务端不预置 Key 也能正常启动（此时没带 Key 的请求会在调用 Pexels 时返回鉴权失败）。
     */
    @PostConstruct
    public void validate() {
        if (!StringUtils.hasText(apiKey)) {
            log.warn("未配置服务端 Pexels API Key（环境变量 PEXELS_API_KEY）："
                    + "stdio 模式下的请求会失败，网络模式请由客户端通过查询参数 ?key= 携带");
        }
        if (!StringUtils.hasText(apiUrl)) {
            throw new IllegalStateException(
                    "未配置 Pexels 接口地址，请检查配置项 pexels.api-url");
        }
    }
}

package com.yupi.yuimagesearchmap.tools;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.yupi.yuimagesearchmap.config.PexelsProperties;
import com.yupi.yuimagesearchmap.config.StreamableTransportConfig;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class ImageSearchTool {

    /** 图片数量的合法区间，防止大模型传入离谱的值（比如 1000） */
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 30;

    /**
     * Pexels 配置（API Key 来自环境变量 PEXELS_API_KEY），由构造器注入
     */
    private final PexelsProperties pexelsProperties;

    public ImageSearchTool(PexelsProperties pexelsProperties) {
        this.pexelsProperties = pexelsProperties;
    }

    /**
     * 搜索图片。
     *
     * 第二个参数 ToolContext 是框架自动注入的隐式参数，不会暴露给大模型，
     * 用来拿到本次请求的传输上下文（从而取出客户端自带的 API Key）。
     */
    @Tool(description = "从 Pexels 搜索图片，返回图片 URL 列表")
    public String searchImage(
            @ToolParam(description = "搜索关键词，例如：猫") String query,
            @ToolParam(description = "期望返回的图片数量，1~30，不填则用服务端默认值", required = false) Integer count,
            ToolContext toolContext) {
        try {
            int limit = resolveCount(count);
            return String.join("\n", searchMediumImage(query, limit, resolveApiKey(toolContext))); // 将字符串列表通过换行拼成一个字符串
        } catch (Exception e) {
            return "Error search image: " + e.getMessage();
        }
    }

    /**
     * 解析本次要返回的图片数量。
     *
     * 优先级：工具参数 count（大模型显式指定）＞ 服务端默认值（PexelsProperties.defaultCount）。
     * 最后再夹到 [MIN_COUNT, MAX_COUNT] 区间内，避免大模型传出离谱的值。
     */
    private int resolveCount(Integer count) {
        int target = (count != null && count > 0) ? count : pexelsProperties.getDefaultCount();
        return Math.min(Math.max(target, MIN_COUNT), MAX_COUNT);
    }

    /**
     * 解析本次调用要用的 Pexels API Key。
     *
     * 优先级：客户端查询参数携带的 Key（?key=）＞ 服务端配置（环境变量 PEXELS_API_KEY）
     *
     * 前者用于「每个用户用自己的 Key」；后者是兜底，
     * stdio 模式没有传输上下文，只能走兜底分支。
     */
    private String resolveApiKey(ToolContext toolContext) {
        if (toolContext != null) {
            Optional<McpSyncServerExchange> exchange = McpToolUtils.getMcpExchange(toolContext);
            if (exchange.isPresent()) {
                var transportContext = exchange.get().transportContext();
                if (transportContext != null) {
                    Object fromClient = transportContext.get(StreamableTransportConfig.AUTHORIZATION_KEY);
                    if (fromClient instanceof String value && StringUtils.hasText(value)) {
                        return value; // 来自客户端查询参数 ?key= 携带的 Key
                    }
                }
            }
        }
        return pexelsProperties.getApiKey(); // 从服务端配置中获取 API Key
    }

    /**
     * 搜索中等尺度的图片列表
     *
     * @param query
     * @param count  期望返回的图片数量
     * @param apiKey Pexels API Key
     * @return
     */
    private List<String> searchMediumImage(String query, int count, String apiKey) {
        // 设置请求头（包含 API 密钥，密钥来自客户端查询参数 ?key= 或服务端环境变量 PEXELS_API_KEY）
        Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", apiKey);

        // 设置请求参数
        Map<String, Object> params = new HashMap<>();
        params.put("query", query);
        params.put("per_page", count); // 让 Pexels 一次只返回 count 张，从源头保证数量稳定

        // 发送 Get 请求
        String response = HttpUtil.createGet(pexelsProperties.getApiUrl())
                .addHeaders(headers)
                .form(params)
                .execute()
                .body(); // 从响应中获取响应体内容

        // 解析相应 JSON（假设响应结构包含“photos”数组，每个元素包含“medium”字段）
        return JSONUtil.parseObj(response)
                .getJSONArray("photos")
                .stream()
                .map(photoObj -> (JSONObject) photoObj)
                .map(photoObj -> photoObj.getJSONObject("src"))
                .map(photo -> photo.getStr("medium"))
                .filter(StrUtil::isNotBlank)
                .limit(count) // 兜底：万一 Pexels 返回的条数多于 per_page，这里再截断一次
                .collect(Collectors.toList()); // 将流中剩余的元素收集到一个 List<String>中并返回
    }
}
/**
 * 假设：
 * API_URL = "https://api.example.com/search"
 * API_KEY = "my-secret-key"
 * query = "cat"
 *
 * 那么Hutool构建并发送的HTTP请求大致如下：
 * GET /search?query=cat HTTP/1.1
 * Host: api.example.com
 * Authorization: my-secret-key
 * User-Agent: Java/Hutool
 * Accept:
 * Connection:keep-alive
 */
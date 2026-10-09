package com.yupi.yuimagesearchmap.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.function.ServerRequest;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.HashMap;
import java.util.Map;

/**
 * 打通「客户端携带的 Key → 工具层」这条通道，只在 Streamable HTTP 模式下生效。
 *
 * 为什么需要这个类？
 * 本服务里的 ImageSearchTool（搜图片）调用 Pexels API 需要 API Key。
 * 期望的效果是：哪个客户端连上来，就用那个客户端自己的 Key，
 * 而不是所有客户端共用服务端配置的那一个。
 *
 * 客户端把 Key 放在 URL 查询参数里带过来，客户端配置形如：
 *   endpoint: /mcp?key=<Pexels API Key>
 *
 * 但 MCP SDK 默认的上下文提取器是 (request) -> McpTransportContext.EMPTY，
 * 会把请求里携带的信息全部丢掉，工具方法根本拿不到，
 * 所以这里覆盖自动配置的传输实现，挂上自定义的 contextExtractor：
 *
 *   客户端查询参数 ?key=
 *     → contextExtractor 取出，放进 McpTransportContext
 *     → SDK 按「每次请求」取出来，塞进 exchange
 *     → ImageSearchTool 通过 ToolContext 拿到 exchange，读出 Key
 *
 * 取值顺序：查询参数 ＞ 服务端环境变量（工具层兜底）。
 *
 * 关于覆盖方式：自动配置里的 WebMvcStreamableServerTransportProvider Bean 带有
 * ConditionalOnMissingBean 条件，因此在本类里定义同名 Bean 即可生效；
 * 而自动配置的 RouterFunction 是通过参数注入该 Provider 的，
 * 会自然拿到这个自定义实例，所以不需要额外补路由 Bean。
 */
@Configuration
// 只在 streamable 协议下生效；stdio / SSE 模式不需要，避免多创建无用的 Bean
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "protocol", havingValue = "STREAMABLE")
public class StreamableTransportConfig {

    /**
     * 存进传输上下文时使用的键名，ImageSearchTool 按这个键名取值。
     */
    public static final String AUTHORIZATION_KEY = "authorizationKey";

    /**
     * 客户端约定的查询参数名，对应客户端配置 endpoint: /mcp?key=xxx
     */
    private static final String QUERY_PARAM_KEY = "key";

    /**
     * 构建 MCP 服务端处理 Streamable HTTP 请求的传输实现。
     *
     * 注意：这个方法本身是「组装接收器」，不是「接收请求」；
     * 真正逐请求处理的是它返回的对象，其中的请求级钩子就是下面挂的 contextExtractor。
     *
     * @param objectMapper Jackson 的 JSON 序列化工具，负责 MCP 协议里 JSON-RPC 报文的转换
     * @param properties   MCP 服务端配置属性，封装 spring.ai.mcp.server.* 下的设置，
     *                     包含接入路径 mcp-endpoint、keep-alive 间隔、是否禁用 DELETE 等
     * @return 自定义的 Streamable HTTP 传输实现
     */
    @Bean
    public WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerObjectMapper") ObjectMapper objectMapper,
            McpServerStreamableHttpProperties properties) {

        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(objectMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .disallowDelete(properties.isDisallowDelete())
                .keepAliveInterval(properties.getKeepAliveInterval())
                // 每个请求都会回调它，把客户端带来的 Key 提取到传输上下文。
                // `类名::方法名` 是 Java 的方法引用，等价于 request -> extractApiKey(request)
                .contextExtractor(StreamableTransportConfig::extractApiKey)
                .build();
    }

    /**
     * 从 URL 查询参数里取出客户端携带的 Key。
     *
     * 没带 ?key= 时返回 EMPTY，工具层会回退到服务端自己的 Key。
     *
     * @param request 当前这一次 HTTP 请求
     * @return 装有 Key 的传输上下文；未携带 Key 时返回空上下文
     */
    private static McpTransportContext extractApiKey(ServerRequest request) {
        String apiKey = UriComponentsBuilder.fromUri(request.uri())
                .build()
                .getQueryParams()
                .getFirst(QUERY_PARAM_KEY);
        // 没带 ?key=：返回空上下文，交给工具层回退到服务端自己的 Key
        if (!StringUtils.hasText(apiKey)) {
            return McpTransportContext.EMPTY;
        }
        Map<String, Object> apiKeyMap = new HashMap<>();
        apiKeyMap.put(AUTHORIZATION_KEY, apiKey);
        return McpTransportContext.create(apiKeyMap);
    }
}
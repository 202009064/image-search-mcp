# image-search-mcp

一个基于 **Spring Boot + Spring AI** 的图片搜索 **MCP Server**。它把「从 Pexels 搜索图片」的能力以标准 MCP 协议暴露出来，任何支持 MCP 的 AI 客户端（Trae、Claude、Cherry Studio 等）都能直接调用。

## 功能特性

- **MCP 协议服务端**：对外暴露 `searchImage` 工具，返回图片 URL 列表。
- **双传输模式**：
  - `stdio`：本地子进程模式，由 AI 客户端直接拉起程序，通过标准输入输出通信。
  - `sse`：服务部署在服务器上，多个客户端通过网络远程连接。
- **每个客户端可用自己的 API Key**：客户端通过 URL 查询参数 `?key=` 携带 Pexels Key；未携带时回退到服务端环境变量。
- **数量安全边界**：返回数量被限制在 `1~30`，防止大模型传入离谱的值。

## 技术栈

| 组件                 | 版本    |
| -------------------- | ------- |
| Java                 | 21      |
| Spring Boot          | 3.5.16  |
| Spring AI MCP Server | 1.1.6   |
| Hutool               | 5.8.46  |
| Lombok               | 1.18.48 |

## 目录结构

```
image-search-mcp
├── src/main/java/com/yupi/yuimagesearchmap
│   ├── YuImageSearchMapApplication.java        # 启动类
│   ├── config
│   │   ├── PexelsProperties.java               # Pexels 配置（api-key / api-url / defaultCount）
│   │   └── StreamableTransportConfig.java      # 网络模式：把客户端 ?key= 透传到工具层
│   └── tools
│       ├── ImageSearchTool.java                # searchImage 工具实现
│       └── McpServerApplication.java
├── src/main/resources
│   ├── application.yaml                        # 主配置：profiles.active 决定用哪套模式
│   ├── application-stdio.yml                   # stdio 模式配置
│   ├── application-sse.yml                     # 网络模式配置（Streamable HTTP）
│   └── logback-spring.xml
└── pom.xml
```

## 快速开始

### 1. 前置条件

- **JDK 21+**（命令行执行 `java -version` 能打出版本即可）
- **Maven**（或直接使用仓库自带的 `mvnw`）
- 一个 [Pexels API Key](https://www.pexels.com/api/)（免费注册即可申请）

### 2. 构建

```bash
./mvnw clean package -DskipTests
```

构建完成后得到 jar：`target/yu-image-search-map-0.0.1-SNAPSHOT.jar`

> 记住这个 jar 的**完整绝对路径**，`绝对路径/target/yu-image-search-map-0.0.1-SNAPSHOT.jar`，后面配置要用。

### 3. 三种使用方案

配置前，先记住三个问题（新手按这三步想就不会乱）：

1. **用哪种模式？** `stdio`（本地子进程）还是 `sse`（网络服务）
2. **服务端怎么处理？** stdio 不用你启动；sse 必须由你先启动
3. **Key 放哪里？** stdio 放客户端 `env`；sse 放客户端 `url` 的 `?key=`（服务端可兜底）

| 方案                    | 模式            | 一句话说明                                 |
| ----------------------- | --------------- | ------------------------------------------ |
| 方案一：本地 stdio 模式 | `stdio`         | 客户端临时拉起 jar，不用自己启动、不占端口 |
| 方案二：本地 HTTP 模式  | `sse`           | 自己先启动服务，客户端按网址连             |
| 方案三：在 Trae 中配置  | `stdio` / `sse` | 方案一、二的客户端配置，在 Trae 里怎么放   |

---

#### 方案一：本地 stdio 模式（新手建议先跑通这个）

**先理解一句话**：stdio 模式下，程序不是「常驻服务」，而是客户端在需要时**临时拉起的一个子进程**，用完即走。所以**你不需要自己启动它，也不会有端口占用**。

**第一步：服务端设置 —— 告诉程序「这次用 stdio」**

项目默认是 `sse` 模式，需要切换。两种方式二选一：

| 方式                    | 怎么做                                                     | 特点                                 |
| ----------------------- | ---------------------------------------------------------- | ------------------------------------ |
| **A. 启动参数（推荐）** | 在客户端 `args` 里加 `-Dspring.profiles.active=stdio`      | 不改项目文件、不用重新打包，最省事   |
| B. 改配置文件           | 把 `application.yaml` 的 `active` 改成 `stdio`，再重新打包 | 一劳永逸，但每次切换模式都要重新打包 |

方式 B 的具体改法（[application.yaml](src/main/resources/application.yaml)），只需把模式改成stdio模式即可：

```yaml
spring:
  profiles:
    active: stdio
```

**第二步：客户端配置 —— 把 jar 交给客户端拉起**

以推荐的**方式 A** 为例（注意 `args` 的顺序）

若你是**自建 Spring AI 客户端**（自己写 Java 程序去连这个服务），在客户端工程的 `application.yaml` 里加上：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        stdio:
          servers-configuration: classpath:mcp-servers.json
```

上面这行只是「声明去读哪个文件」，真正描述怎么拉起服务的是 `mcp-servers.json`（放在客户端工程的 `src/main/resources/` 下，Trae / Claude Desktop 通用格式）：
```json
{
  "mcpServers": {
    "image-search-mcp": {
      "command": "java",
      "args": [
        "-Dspring.profiles.active=stdio",
        "-jar",
        "绝对路径/target/yu-image-search-map-0.0.1-SNAPSHOT.jar"
      ],
      "env": {
        "PEXELS_API_KEY": "你的 Pexels API Key"
      }
    }
  }
}
```

| 字段                 | 填什么                                                   |
| -------------------- | -------------------------------------------------------- |
| `command`            | 装好 JDK 21 就是 `java`                                  |
| `args[0]`            | `-Dspring.profiles.active=stdio`，**必须在 `-jar` 前面** |
| `args[2]`            | 上一步记下的 jar **绝对路径**                            |
| `env.PEXELS_API_KEY` | 你的 Pexels Key，**必填**                                |

**第三步：验证**

在客户端里让它调用 `searchImage` 搜索「猫」，能返回一串图片 URL 就成功了。

**新手最容易踩的 3 个坑**

1. `-Dspring.profiles.active=stdio` **必须写在 `-jar` 之前**（它是给 JVM 的参数，顺序错了不生效）。
2. 路径要用**绝对路径**；JSON 里写反斜杠必须双写 `\\`，或者干脆统一用正斜杠 `/`（Windows 也认）。
3. stdio 模式**没有网络上下文，`?key=` 无效**，Key 只能放在 `env.PEXELS_API_KEY` 里。

---

#### 方案二：本地 HTTP 模式（Streamable HTTP，配置为 `sse`）

**先理解一句话**：这一模式下，程序是一个**常驻的网络服务**，必须**由你先启动、并保持运行**；客户端再通过网络连上来。

**第一步：服务端设置 —— 先启动服务**

1. 确认用 `sse` 模式（[application.yaml](src/main/resources/application.yaml) **默认就是** `sse`，通常无需改动）：

   ```yaml
   spring:
     profiles:
       active: sse
   server:
     port: 8127
   ```

2. 启动服务，Key 有两种给法（任选一种）：

   ```bash
   # 方式一：命令行参数
   java -jar target/yu-image-search-map-0.0.1-SNAPSHOT.jar --pexels.api-key=你的 Pexels API Key
   ```

   ```powershell
   # 方式二：先设环境变量再启动（Key 不出现在命令行里）
   # Windows PowerShell
   $env:PEXELS_API_KEY="你的 Pexels API Key"
   java -jar target/yu-image-search-map-0.0.1-SNAPSHOT.jar
   ```

   > 这里的 Key 是**服务端自己的 Key，属于兜底**：客户端带了 `?key=` 就用客户端的，没带才用这个。想让每个用户用自己的 Key，服务端甚至可以先不配。

   > 启动后**不要关闭这个终端窗口**，关了服务就停了。

**第二步：客户端配置 —— 按网址连**

若你是**自建 Spring AI 客户端**，在客户端工程的 `application.yaml` 里加上：

```yaml
spring:
  ai:
    mcp:
      client:
        enabled: true
        streamable-http:
          connections:
            image-search-mcp:
              url: http://localhost:8127
              endpoint: /mcp?key=你的 Pexels API Key
```

> 若用的是 Trae / Claude Desktop 这类通用 MCP 客户端，则改填 `url`（见方案三），格式不同但含义一致。

- `url` 是服务端地址，`/mcp` 是固定接入路径。
- `?key=` 可选：带上就用这个 Key，不带则用服务端启动时配的 Key。
- 服务在远程服务器时，把 `url: http://localhost:8127` 换成服务器地址，例如 `url: http://106.53.54.220:8127`。

**第三步：验证**

在服务器开启的情况下，浏览器访问 `http://localhost:8127/mcp` ，返回 `Invalid Accept header. Expected TEXT_EVENT_STREAM` 就说明服务已就绪（这是 MCP 的正常协议响应，**不是报错**）。

**新手最容易踩的 3 个坑**

1. **必须先启动服务**，否则客户端连不上（这点和 stdio 正好相反）。
2. `?key=` 直接拼在 `url` 后面，注意不要多空格、不要漏掉 `?`。
3. 连远程服务器时，要确保服务器防火墙 / 云安全组已放行 `8127` 端口。

---

#### 方案三：在 Trae 中配置

上面两种模式的**客户端配置**都能直接用在 Trae 里，这一节只说明在 Trae 中的放置位置和操作步骤。

**1）找到 MCP 配置文件**（任选其一）

- 全局配置文件：`C:\Users\<用户名>\AppData\Roaming\Trae CN\User\mcp.json`
- 或打开 Trae → 设置 → **MCP** → 添加 → **手动配置**，粘贴 JSON

**2）粘贴对应模式的配置**（二选一，或两个都加）

- 想用 **stdio**：粘贴「方案一 → 第二步」的 JSON（由 Trae 拉起 jar，无需另启服务）
- 想用 **HTTP**：粘贴「方案二 → 第二步」的 JSON（需先启动服务）

若两个都想加，可在 `mcpServers` 里写两条，用不同的名字区分：

```json
{
  "mcpServers": {
    "image-search-stdio": {
      "command": "java",
      "args": [
        "-Dspring.profiles.active=stdio",
        "-jar",
        "绝对路径/target/yu-image-search-map-0.0.1-SNAPSHOT.jar"
      ],
      "env": {
        "PEXELS_API_KEY": "你的 Pexels API Key"
      }
    },
    "image-search-http": {
      "url": "http://localhost:8127/mcp?key=你的 Pexels API Key"
    }
  }
}
```

**3）验证**：保存后在 Trae 的 **MCP 面板**确认 `image-search-mcp` 状态为「已连接」，然后在对话里让它调用 `searchImage` 即可。

> 两种模式的区别：stdio 由 Trae 自己拉起 jar、不占端口、Key 只能走 `env`；HTTP 需要先启动服务、可远程共享、Key 可走 `?key=`。

#### 方案四：远程部署（直接用作者已部署的服务）

**先理解一句话**：作者已把服务部署在公网服务器上并保持运行，你**不需要自己搭建或启动**，让客户端直接连下面的地址即可。

服务地址：`http://image-search-mcp.websi.vip:8127/mcp`

**怎么用**：把方案二、方案三里客户端配置的本机地址 `http://localhost:8127` 换成 `http://image-search-mcp.websi.vip:8127`，其余配置保持不变。

以 Trae 为例（对应方案三第 2 步）：

```json
{
  "mcpServers": {
    "image-search-http": {
      "url": "http://image-search-mcp.websi.vip:8127/mcp?key=你的 Pexels API Key"
    }
  }
}
```

> 三点注意：
> 1. 端口 `8127` 和路径 `/mcp` 都不能省略。
> 2. 请通过 `?key=` 带上**你自己的** Pexels Key，走自己的额度；不带则依赖服务端是否预置了 Key。
> 3. 这是作者的私人服务器，仅作体验分享，随时可能失效；正式使用请按方案二自行部署。

**验证**：保存后在 Trae 的 **MCP 面板**确认状态为「已连接」，再让它调用 `searchImage` 搜索「猫」即可。

---

## 配置说明

| 配置项                 | 环境变量         | 默认值                             | 说明                                    |
| ---------------------- | ---------------- | ---------------------------------- | --------------------------------------- |
| `pexels.api-key`       | `PEXELS_API_KEY` | 空                                 | Pexels API Key，为空时启动只告警不阻断  |
| `pexels.api-url`       | —                | `https://api.pexels.com/v1/search` | Pexels 搜索接口地址，缺失会导致启动失败 |
| `pexels.default-count` | —                | `5`                                | 未指定数量时的默认返回张数              |
| `server.port`          | —                | `8127`                             | 网络模式监听端口                        |

**API Key 取值优先级**：客户端查询参数 `?key=` ＞ 环境变量 `PEXELS_API_KEY` ＞ 配置文件默认值。

## MCP 工具

### `searchImage`

从 Pexels 搜索图片，返回图片 URL 列表（换行分隔）。

| 参数    | 类型    | 必填 | 说明                                                  |
| ------- | ------- | ---- | ----------------------------------------------------- |
| `query` | String  | 是   | 搜索关键词，例如 `猫`                                 |
| `count` | Integer | 否   | 期望返回的图片数量，范围 `1~30`，不填使用服务端默认值 |

返回示例：

```
https://images.pexels.com/photos/xxx/pexels-photo-xxx.jpeg?auto=compress&cs=tinysrgb&h=350
https://images.pexels.com/photos/yyy/pexels-photo-yyy.jpeg?auto=compress&cs=tinysrgb&h=350
```

## 部署

以腾讯云轻量应用服务器 + 宝塔面板为例：

```bash
/www/server/java/jdk-21.0.2/bin/java \
  -Xmx512m -Xms256m \
  -jar /www/wwwroot/yu-image-search-map-0.0.1-SNAPSHOT.jar \
  --server.port=8127 \
  --pexels.api-key=你的 Pexels API Key
```

部署后需放行端口：

- 服务器防火墙（如宝塔防火墙）放行 TCP `8127`
- 云厂商安全组 / 防火墙放行 TCP `8127`

验证：浏览器访问 `http://<服务器地址>:8127/mcp`，返回 `Invalid Accept header. Expected TEXT_EVENT_STREAM` 说明服务对外可达（这是正常的 MCP 协议响应）。

## 常见问题

**Q：启动时提示未配置 Pexels API Key？**
A：这是告警而非错误。网络模式下可由客户端通过 `?key=` 携带 Key；stdio 模式必须在客户端 `env` 里配置 `PEXELS_API_KEY`。

**Q：为什么用 Streamable HTTP 而不是旧的 SSE？**
A：MCP 1.1.6 起 SSE 传输已被标记为废弃，且旧 SSE 拿不到请求中携带的信息，无法实现「每个客户端用自己的 Key」。

**Q：搜索返回鉴权失败？**
A：检查 Key 是否正确，或客户端是否携带了 `?key=`。

**Q：客户端显示连接失败 / 工具没出现？**
A：stdio 先检查 `-Dspring.profiles.active=stdio` 是否写在 `-jar` 前面、jar 路径是否正确；HTTP 先检查服务是否已在运行、`url` 是否可访问。

## License

本项目基于 [MIT License](LICENSE) 开源。
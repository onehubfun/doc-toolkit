# ==============================================================================
# 基础环境镜像：JDK 21 + Playwright(Chromium) + LibreOffice Headless
# ==============================================================================
# 这个镜像提供的是"环境"，不是绑死的单一程序：
#   - 默认跑内置的「网站转 DOCX」工具（历史兼容行为）
#   - 可以切换成内置的「网站转 PDF」工具（TOOL=pdf）
#   - 也可以完全换成你自己的 Java 程序（APP_JAR / APP_MAIN_CLASS）
# 具体配置方式见 README.md。
#
# 构建：
#   docker build -t doc-toolkit:latest .
# ==============================================================================

# ---------------------- 构建阶段 ----------------------
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /build

RUN apt-get update && apt-get install -y --no-install-recommends \
        maven ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests -B

# 只下载 Chromium 浏览器二进制（不要 --with-deps，系统依赖留到运行阶段装，
# 构建阶段装的系统依赖不会带到运行阶段的全新基础镜像里，装了也是白装）
RUN mkdir -p /opt/playwright \
    && mvn dependency:build-classpath -DincludeScope=runtime -Dmdep.outputFile=/tmp/cp.txt \
    && PW_JAR=$(find ~/.m2/repository/com/microsoft/playwright -name "playwright-*.jar" | head -1) \
    && PLAYWRIGHT_BROWSERS_PATH=/opt/playwright java -cp "$PW_JAR":$(cat /tmp/cp.txt) com.microsoft.playwright.CLI install chromium

# ---------------------- 运行阶段 ----------------------
# 用 JDK（不是 JRE）：这个镜像定位是"给你跑自己 Java 程序的环境"，
# 用户的程序可能需要 javac/jshell 等开发工具，不只是运行现成 jar。
FROM eclipse-temurin:21-jdk-jammy

LABEL description="JDK 21 + Playwright(Chromium) + LibreOffice Headless —— 环境镜像，可跑内置工具也可跑你自己的 Java 程序"

ENV LANG=zh_CN.UTF-8 \
    LC_ALL=zh_CN.UTF-8 \
    DEBIAN_FRONTEND=noninteractive \
    PLAYWRIGHT_BROWSERS_PATH=/app/playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    OUTPUT_DIR=/output

# 只装这个环境必需的系统包：
#   libreoffice-writer + libreoffice-pdfimport —— PDF <-> DOCX 转换核心
#   fonts-liberation                          —— docx 里的 Word 兼容西文字体
#   fonts-noto-cjk / fonts-wqy-zenhei          —— 中文渲染
#   ca-certificates                           —— HTTPS 证书信任
#   tini                                      —— PID 1，回收 headless chromium/soffice 留下的僵尸进程
RUN apt-get update && apt-get install -y --no-install-recommends \
        libreoffice-writer \
        libreoffice-pdfimport \
        fonts-liberation \
        fonts-noto-cjk \
        fonts-wqy-zenhei \
        ca-certificates \
        tini \
    && rm -rf /var/lib/apt/lists/* \
    && locale-gen zh_CN.UTF-8

WORKDIR /app

# 复制构建阶段准备好的 Chromium 浏览器
COPY --from=builder /opt/playwright /app/playwright

# 复制内置工具的 JAR（用精确文件名匹配，避免连 shade 插件生成的 original-*.jar
# 瘦包一起匹配到，那个瘦包不含任何第三方依赖类）
COPY --from=builder /build/target/doc-converter-*.jar /app/app.jar

# 只装 Chromium 运行所需的系统共享库（不是不带参数装全部浏览器的 install-deps）
RUN java -cp /app/app.jar com.microsoft.playwright.CLI install-deps chromium \
    && rm -rf /var/lib/apt/lists/*

# 通用入口脚本：决定到底跑内置工具还是用户自己的程序，见 README.md
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir -p /output /app/user

# 使用 tini 作为 PID 1，避免 headless 浏览器/soffice 留下僵尸进程
ENTRYPOINT ["/usr/bin/tini", "--", "/entrypoint.sh"]

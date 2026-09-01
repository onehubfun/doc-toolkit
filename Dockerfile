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

# ---------------------- sdenv 构建阶段 ----------------------
# 单独一个阶段装 Node.js + node-gyp 编译工具链 + sdenv（用于绕过瑞数 RuiShu
# 反爬网关，见 scripts/rs-fetch.js 头部注释和 README「遇到瑞数防护的网站怎么办」
# 一节）。之所以单独开一个阶段而不是直接在运行阶段装：sdenv 依赖 canvas 这个
# 需要 node-gyp 现场编译原生模块的包，编译期需要 python3/make/g++/一堆 -dev
# 头文件包，这些东西运行时用不上，编译完只把产物（Node 运行时 + 编译好的
# node_modules）拷到最终阶段，运行阶段只装 canvas 需要的运行时共享库
# （不带 -dev 后缀那些），避免把几百 MB 的编译工具链一起带进最终镜像。
#
# 用的是和最终运行阶段同一个基础镜像（Ubuntu 22.04 jammy），保证编译出来的
# 原生模块和运行阶段的 glibc/系统库版本完全匹配——不能跨发行版本随便复制别人
# 镜像里编译好的 .node 文件，亲测会因为 glibc 版本不一致直接报错。
FROM eclipse-temurin:21-jdk-jammy AS sdenv-builder

ENV DEBIAN_FRONTEND=noninteractive

# 装 Node.js 20.x（NodeSource 官方仓库；sdenv 自己验证过 v20.19.5 兼容，
# 其他大版本不保证，参见 https://github.com/pysunday/sdenv#依赖）
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl gnupg \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" > /etc/apt/sources.list.d/nodesource.list \
    && apt-get update && apt-get install -y --no-install-recommends nodejs \
    && rm -rf /var/lib/apt/lists/*

# node-gyp 编译工具链 + canvas 编译时需要的图形库开发包（-dev 版本，带头文件）
RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 make g++ pkg-config \
        libcairo2-dev libpango1.0-dev libjpeg-dev libgif-dev librsvg2-dev \
    && rm -rf /var/lib/apt/lists/*

# 全局安装 sdenv，会自动触发 node-gyp rebuild 针对当前系统编译原生模块
RUN npm install -g sdenv

# ---------------------- 运行阶段 ----------------------
# 用 JDK（不是 JRE）：这个镜像定位是"给你跑自己 Java 程序的环境"，
# 用户的程序可能需要 javac/jshell 等开发工具，不只是运行现成 jar。
FROM eclipse-temurin:21-jdk-jammy

LABEL description="JDK 21 + Playwright(Chromium) + LibreOffice Headless + Node.js(sdenv) —— 环境镜像，可跑内置工具也可跑你自己的 Java 程序"

ENV LANG=zh_CN.UTF-8 \
    LC_ALL=zh_CN.UTF-8 \
    DEBIAN_FRONTEND=noninteractive \
    PLAYWRIGHT_BROWSERS_PATH=/app/playwright \
    PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1 \
    OUTPUT_DIR=/output \
    NODE_PATH=/usr/lib/node_modules

# 只装这个环境必需的系统包：
#   libreoffice-writer + libreoffice-pdfimport —— PDF <-> DOCX 转换核心
#   fonts-liberation                          —— docx 里的 Word 兼容西文字体
#   fonts-noto-cjk / fonts-wqy-zenhei          —— 中文渲染
#   ca-certificates                           —— HTTPS 证书信任
#   tini                                      —— PID 1，回收 headless chromium/soffice 留下的僵尸进程
#   nodejs                                    —— 跑 scripts/rs-fetch.js（sdenv）
#   libcairo2/libpango-1.0-0/libjpeg8/libgif7/librsvg2-2 —— canvas 运行时共享库
#     （注意都不带 -dev 后缀，编译期用的头文件包留在 sdenv-builder 阶段，不带过来）
RUN apt-get update && apt-get install -y --no-install-recommends \
        libreoffice-writer \
        libreoffice-pdfimport \
        fonts-liberation \
        fonts-noto-cjk \
        fonts-wqy-zenhei \
        ca-certificates \
        tini \
        curl gnupg \
    && mkdir -p /etc/apt/keyrings \
    && curl -fsSL https://deb.nodesource.com/gpgkey/nodesource-repo.gpg.key | gpg --dearmor -o /etc/apt/keyrings/nodesource.gpg \
    && echo "deb [signed-by=/etc/apt/keyrings/nodesource.gpg] https://deb.nodesource.com/node_20.x nodistro main" > /etc/apt/sources.list.d/nodesource.list \
    && apt-get update && apt-get install -y --no-install-recommends \
        nodejs \
        libcairo2 libpango-1.0-0 libjpeg8 libgif7 librsvg2-2 \
    && rm -rf /var/lib/apt/lists/* \
    && locale-gen zh_CN.UTF-8

WORKDIR /app

# 复制构建阶段准备好的 Chromium 浏览器
COPY --from=builder /opt/playwright /app/playwright

# 复制内置工具的 JAR（用精确文件名匹配，避免连 shade 插件生成的 original-*.jar
# 瘦包一起匹配到，那个瘦包不含任何第三方依赖类）
COPY --from=builder /build/target/doc-converter-*.jar /app/app.jar

# 复制 sdenv-builder 阶段编译好的 sdenv（含针对当前系统编译好的原生模块），
# 不需要带上编译期的 -dev 头文件包和 gcc/python3 这些工具链
COPY --from=sdenv-builder /usr/lib/node_modules/sdenv /usr/lib/node_modules/sdenv

# 瑞数(RuiShu)绕过脚本，配合 sdenv 使用；见 README「遇到瑞数防护的网站怎么办」
COPY scripts/rs-fetch.js /app/rs-fetch.js

# 只装 Chromium 运行所需的系统共享库（不是不带参数装全部浏览器的 install-deps）
RUN java -cp /app/app.jar com.microsoft.playwright.CLI install-deps chromium \
    && rm -rf /var/lib/apt/lists/*

# 通用入口脚本：决定到底跑内置工具还是用户自己的程序，见 README.md
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh && mkdir -p /output /app/user

# 使用 tini 作为 PID 1，避免 headless 浏览器/soffice 留下僵尸进程
ENTRYPOINT ["/usr/bin/tini", "--", "/entrypoint.sh"]

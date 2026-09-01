# doc-toolkit:latest —— JDK 21 + Playwright(Chromium) + LibreOffice Headless

一个可以直接对外提供的 Docker 环境镜像：内置"网站转 DOCX / 网站转 PDF"两个开箱即用的小工具,同时也可以当成基础环境镜像,跑你自己的 Java 程序。

```
浏览器渲染(Playwright/Chromium) → 打印成 PDF → LibreOffice 转成 DOCX
```

## 目录

- [镜像里有什么](#镜像里有什么)
- [快速开始](#快速开始)
- [环境变量](#环境变量)
- [跑你自己的程序](#跑你自己的程序)
- [遇到瑞数(RuiShu)防护的网站怎么办](#遇到瑞数ruishu防护的网站怎么办)
- [构建镜像](#构建镜像)
- [设计说明 / 已知限制](#设计说明--已知限制)
- [常见问题](#常见问题)

> **本次更新(相对上一版)**:新增了应对"瑞数(RuiShu)"反爬网关的处理路径——`scripts/rs-fetch.js` +
> `HtmlToPdf`/`HtmlToDocx` 两个新工具类。详见下方[遇到瑞数(RuiShu)防护的网站怎么办](#遇到瑞数ruishu防护的网站怎么办)一节。
> 这条路径**还没有接入 `Dockerfile`/`entrypoint.sh` 的自动化流程**，目前是手动两步跑，如实记录，不夸大集成程度。

---

## 镜像里有什么

| 组件 | 说明 |
|---|---|
| JDK 21(Eclipse Temurin,**不是** JRE) | 运行环境;特意保留 `javac` 等开发工具,方便你在容器里直接跑/编译自己的 Java 程序 |
| Playwright 1.50.0 + Chromium | 只装 Chromium 一个浏览器内核(不含 Firefox/WebKit/ffmpeg),内置工具只用得到 Chromium |
| LibreOffice(`writer` + `pdfimport`) | 只装 PDF→DOCX 转换用得到的最小组件集,不含 Impress/Calc/Draw |
| 字体:`fonts-liberation` / `fonts-noto-cjk` / `fonts-wqy-zenhei` | Word 兼容西文字体 + 中文渲染 |
| 内置工具 jar(`/app/app.jar`) | 四个 CLI 工具:`UrlToDocx`(默认)、`UrlToPdf`、`HtmlToPdf`、`HtmlToDocx`(后两个配合 `scripts/rs-fetch.js` 处理瑞数防护网站,见下文) |

镜像大小约 **3GB**(JDK 版;如果你不需要 `javac` 只想跑现成 jar,把最终阶段基础镜像换成 `eclipse-temurin:21-jre-jammy` 能再省 ~270MB,见[构建镜像](#构建镜像))。

## 快速开始

```bash
mkdir -p output
docker build -t doc-toolkit:latest .
```

### 网站转 DOCX(默认行为)

```bash
docker run --rm -v "$(pwd)/output:/output" doc-toolkit:latest "https://example.com"

# 指定输出文件名
docker run --rm -v "$(pwd)/output:/output" doc-toolkit:latest "https://example.com" /output/my.docx
```

不带 `http(s)://` 前缀也行,会自动补 `https://`。不指定输出文件名时,会用"页面标题 + 时间戳"自动命名,保存到 `/output`(挂载卷)。

### 网站转 PDF

```bash
docker run --rm -v "$(pwd)/output:/output" -e TOOL=pdf doc-toolkit:latest "https://example.com"
```

## 环境变量

### 内置工具通用

| 变量 | 默认值 | 说明 |
|---|---|---|
| `TOOL` | `docx` | `docx` 跑 `UrlToDocx`,`pdf` 跑 `UrlToPdf`。只在**没设置 `APP_JAR`** 时生效 |
| `OUTPUT_DIR` | `/output` | 不指定输出文件名时的默认保存目录 |
| `NAV_TIMEOUT_MS` | `30000` | 页面导航超时(毫秒) |
| `EXTRA_WAIT_MS` | docx:`3000` / pdf:`2000` | `networkidle` 之后的额外等待时间(毫秒),给懒加载内容留缓冲 |
| `USER_AGENT` | 桌面版 Chrome UA(见下方说明) | 覆盖默认的伪装 UA |

### 跑自己程序时用(见下一节)

| 变量 | 说明 |
|---|---|
| `APP_JAR` | 你的 jar 路径。**一旦设置这个变量,内置工具完全不会被调用** |
| `APP_MAIN_CLASS` | 你的程序入口类全限定名。不设置的话,会当作可执行 jar(要求 manifest 里有 `Main-Class`)来跑 |
| `APP_CLASSPATH` | 额外的 classpath(比如你的 jar 不是 fat jar,需要挂载并引用其他依赖 jar 时用),用 `:` 分隔 |
| `JAVA_OPTS` | 传给 `java` 命令的额外 JVM 参数,比如 `-Xmx512m` |

`docker run` 命令行里跟在镜像名后面的所有参数,都会原样转发给最终运行的程序(内置工具或你自己的程序),这个行为不受上面哪种模式影响。

## 跑你自己的程序

这个镜像不是"一次性写死的工具",而是一个可复用的环境:JDK 21 + Playwright(Chromium) + LibreOffice 都装好了,你可以直接拿来跑自己的 Java 程序,不用重新踩一遍"LibreOffice 转 docx 要指定 `--infilter=writer_pdf_import`""Playwright 默认浏览器集合会在运行时偷偷联网下载"这些坑(踩坑过程见[设计说明](#设计说明--已知限制))。

### 方式一:环境变量 + 挂载 jar(不用重新 build 镜像)

```bash
docker run --rm \
  -v "$(pwd)/my-app.jar:/app/user/my-app.jar:ro" \
  -e APP_JAR=/app/user/my-app.jar \
  -e APP_MAIN_CLASS=com.yourcompany.Main \
  doc-toolkit:latest arg1 arg2
```

如果你的 jar 本身是可执行 jar(manifest 里有 `Main-Class`),连 `APP_MAIN_CLASS` 都不用设:

```bash
docker run --rm \
  -v "$(pwd)/my-app.jar:/app/user/my-app.jar:ro" \
  -e APP_JAR=/app/user/my-app.jar \
  doc-toolkit:latest arg1 arg2
```

你的程序里如果也想用 Playwright/LibreOffice,直接调用就行,环境变量(`PLAYWRIGHT_BROWSERS_PATH` 等)已经配置好了。

### 方式二:派生镜像(适合需要把程序永久固化进镜像的场景)

```dockerfile
FROM doc-toolkit:latest
COPY my-app.jar /app/user/my-app.jar
ENV APP_JAR=/app/user/my-app.jar
ENV APP_MAIN_CLASS=com.yourcompany.Main
```

### 方式三:完全自定义入口(最大自由度)

不想走上面两种约定,直接用 Docker 原生的 `--entrypoint` 覆盖:

```bash
docker run --rm --entrypoint bash doc-toolkit:latest
docker run --rm --entrypoint java doc-toolkit:latest -cp /app/user/my-app.jar com.yourcompany.Main
```

## 遇到瑞数(RuiShu)防护的网站怎么办

标准 `UrlToDocx`/`UrlToPdf`(不管怎么伪装 UA、打补丁隐藏 `navigator.webdriver`)对"瑞数"这类反爬网关一律失败——实测过标准 Chromium、Camoufox(Firefox 内核指纹伪装)、CloakBrowser(号称"过所有检测"的商业方案)三种真实浏览器方案,全部卡在同一步:JS 挑战能跑,生成的验证 cookie 也发出去了,但服务端返回 400。猜测是瑞数检测的是"这个浏览器是不是被 CDP/自动化协议接管"这个底层信号,不是简单的指纹伪装能绕过的(不管伪装得多像真人,只要是 Playwright/Puppeteer 控制的真实浏览器,这条底层痕迹都在)。

真正验证有效的是 [sdenv](https://github.com/pysunday/sdenv)——它不启动真实浏览器,而是用改造过的 `jsdom` 在 Node.js 里模拟浏览器环境去跑瑞数的验证 JS。因为它根本不是被 CDP 控制的真实浏览器,天然绕开了上面那个检测点。

### 完整流程(目前是手动两步,还没接入 Dockerfile)

```bash
# 第一步：用 sdenv 的官方镜像把瑞数验证过一遍，拿到真实 HTML
docker run --rm \
  -v "$(pwd)/scripts/rs-fetch.js:/app/myapp:ro" \
  -v "$(pwd)/output:/output" \
  -e NODE_PATH=/usr/local/lib/node_modules \
  pysunday/sdenv-arm64:latest myapp "https://目标网址" /output/page.html

# 第二步：用 doc-toolkit 把这段 HTML 渲染成 PDF 或 DOCX
# （需要用 --entrypoint 覆盖默认入口，直接指定跑 HtmlToPdf/HtmlToDocx）
docker run --rm --entrypoint java \
  -v "$(pwd)/output:/output" \
  doc-toolkit:latest \
  -cp /app/app.jar com.example.converter.HtmlToDocx /output/page.html "https://目标网址所在域名/" /output/result.docx
```

`scripts/rs-fetch.js` 的退出码约定(方便写脚本判断分支):

| 退出码 | 含义 |
|---|---|
| `0` | 是瑞数网站,已成功拿到内容,HTML 写入指定文件 |
| `2` | **不是**瑞数网站,不算错误,应该直接用标准 `UrlToDocx`/`UrlToPdf` |
| `1` | 是瑞数网站,但流程失败(超时/验证不通过/网络错误),stdout 最后一行 JSON 里带具体 `reason` |

`HtmlToPdf`/`HtmlToDocx` 拿到 HTML 之后,不会再对目标网址发起新的 `page.navigate()`(那样又会撞回瑞数拦截),而是用 `page.setContent()` 在本地把这段 HTML 注入渲染,并在 `<head>` 里插入 `<base href="...">` 让相对路径的图片/资源能正确解析下载。

```bash
java -cp app.jar com.example.converter.HtmlToPdf  <html文件路径> <baseUrl> [output.pdf]
java -cp app.jar com.example.converter.HtmlToDocx <html文件路径> <baseUrl> [output.docx]
```

**已知限制**:这条路径只验证过"瑞数保护 + 服务端渲染(SSR)"的网站。`rs-fetch.js` 最后一步是用 Node 内置 `fetch()` 拿服务器原始响应文本,**不会执行目标页面自己的 JS**——如果目标是 Vue/React 这类客户端动态渲染(CSR)的 SPA,大概率只能拿到一个空壳(`<div id="app"></div>`),内容还没来得及渲染。真遇到这种站点需要额外改造(见对话记录里的分析),没有现成方案。

## 构建镜像

```bash
docker build -t doc-toolkit:latest .
```

多阶段构建,builder 阶段装 Maven + 下载依赖 + 编译 + 下载 Chromium 浏览器二进制;运行阶段是全新的基础镜像,只拷贝编译产物和浏览器文件,不会带上 builder 阶段装的 apt 包。

**想要更小的镜像(用 JRE 替代 JDK)**:如果你确定不需要在容器里编译代码,把 `Dockerfile` 里运行阶段的这一行:

```dockerfile
FROM eclipse-temurin:21-jdk-jammy
```

换成:

```dockerfile
FROM eclipse-temurin:21-jre-jammy
```

能再省下约 270MB(`javac` 等开发工具会跟着没了,只能跑现成的 jar)。

## 设计说明 / 已知限制

这套方案是经过多轮实测比较后定下来的,记录一下关键决策,免得以后有人重新踩一遍坑:

- **为什么是"Playwright 打印 PDF → LibreOffice 转 DOCX",而不是直接读 HTML 转 DOCX?**
  实测过 pandoc(HTML reader 解析不了现代前端框架输出的深层嵌套 DOM,几乎抓不到正文)和 LibreOffice 直接读 HTML(能抓到文本但混进导航栏噪音,且图片全部丢失,因为不会主动下载远程图片)。`page.pdf()` 走 Chromium 的打印引擎,会自动应用网站的 `@media print` 样式表去噪,且图片在渲染时已经被"烧录"进 PDF 内容流,LibreOffice 转 docx 时能把这些图片原样保留——这是当前最省事、效果最好的路径。

- **为什么 LibreOffice 转换要加 `--infilter=writer_pdf_import`?**
  LibreOffice 默认把 PDF 当 Draw(绘图)文档打开,Draw 文档没有到 docx 的导出路径,不加这个参数会报 `no export filter for xxx.docx found, aborting`。

- **为什么不用 PDFBox / pandoc / pdf2docx 做 PDF→DOCX?**
  PDFBox 只能抽取纯文本,图片和排版全部丢失。pandoc 根本不支持读取 PDF(`pandoc --list-input-formats` 里没有 `pdf`)。`pdf2docx`(Python)实测虽然能识别出真表格,但文本反而更少、文件体积暴涨 30 倍(遇到复杂区域会整体栅格化成一张大图),而且版本兼容性脆弱。

- **为什么设置了 `USER_AGENT`?**
  部分网站(尤其国内政府站点常见的 WAF/反爬网关)会检测 UA 里的 `HeadlessChrome` 字样直接空响应断连(`net::ERR_EMPTY_RESPONSE`),换成普通桌面 Chrome UA 就能正常访问。

- **为什么设置了 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`?**
  Playwright Java 客户端在 `Playwright.create()` 时会校验"默认浏览器集合"(chromium/firefox/webkit/ffmpeg)是否齐全,即使代码只用 Chromium,缺了另外几个也会在运行时自动联网下载(~170MB,多等 20+ 秒),还会在日志里打一堆"missing dependencies"警告。这个镜像只装 Chromium,靠这个环境变量关掉自动补齐行为。

- **镜像瘦身做了什么(从最初 5GB 降到现在 ~3GB)**:
  - LibreOffice 只装 `writer` + `pdfimport`(实测卸载 Impress/Calc/Draw 不影响转换结果);
  - Playwright 只装 Chromium,不装 Firefox/WebKit/ffmpeg 的二进制和系统依赖(GStreamer/GTK4 那一整套,不装比装了消警告更省空间);
  - fat jar 里排除了 Playwright 依赖为 mac/mac-arm64/win32_x64 三个平台各自打包的完整 Node.js 驱动(容器只跑 Linux,用不上,省了 ~300MB);
  - 去掉了从未被实际调用的 `pdfbox`/`poi-ooxml` 依赖和 `spring-boot-maven-plugin`。

- **已知限制**:
  - 这条链路本质是"打印版式"的 PDF 转换,不是语义级的文档对象重建——文字是按视觉位置摆放的文本框,不是真正流式的段落;原网页里的 `<table>` 也不会变成 docx 里可编辑的表格。如果你的场景强依赖"可编辑表格结构",可以了解一下 `pdf2docx`(免费但不稳定)或 Aspose.PDF for Java(商业,本项目未采用,具体见对话记录里的调研结论)。
  - 复杂到 LibreOffice/Chromium 渲染不了的区域(极少数情况)可能被处理成整页截图。

## 常见问题

**Q: 能同时转多个网址吗?**
不行,一次 `docker run` 只处理一个 URL。要批量处理,自己写个脚本循环调用,或者用 [方式一](#方式一环境变量--挂载-jar不用重新-build-镜像)接自己的批处理程序。

**Q: 转换出来的中文显示成方块怎么办?**
镜像里已经装了 `fonts-noto-cjk` + `fonts-wqy-zenhei`,正常中文网站不会有这个问题。如果目标站点用了生僻字体,需要自己额外装对应字体包。

**Q: 为什么日志里有个 "Host system is missing dependencies" 的警告?**
如果你看到这条,说明镜像没设 `PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1`,或者手动改了 `TOOL`/`APP_JAR` 之类的配置。默认配置下不应该出现这条警告;出现了请检查环境变量是否被意外覆盖。

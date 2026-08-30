#!/bin/bash
# ==============================================================================
# 通用入口脚本
#
# 这个镜像本质上是一个"环境"：JDK 21 + Playwright(Chromium) + LibreOffice。
# 具体跑什么程序，按下面的优先级决定，方便你在不重新 build 镜像的情况下
# 换成自己的 Java 程序：
#
#   优先级 1：设置了 APP_JAR                 -> 运行你自己的程序
#   优先级 2：没设 APP_JAR，但设了 TOOL=pdf   -> 运行内置的 网站转 PDF 工具
#   优先级 3：什么都没设                      -> 运行内置的 网站转 DOCX 工具（默认，向后兼容）
#
# 三种情况下，`docker run` 命令行传的参数都会原样转发给最终运行的 Java 程序。
# ==============================================================================
set -euo pipefail

if [ -n "${APP_JAR:-}" ]; then
    CLASSPATH="${APP_JAR}"
    if [ -n "${APP_CLASSPATH:-}" ]; then
        CLASSPATH="${CLASSPATH}:${APP_CLASSPATH}"
    fi

    if [ -n "${APP_MAIN_CLASS:-}" ]; then
        exec java ${JAVA_OPTS:-} -cp "${CLASSPATH}" "${APP_MAIN_CLASS}" "$@"
    else
        # 没给 Main-Class 就假定 APP_JAR 是一个可执行 jar（manifest 里带 Main-Class）
        exec java ${JAVA_OPTS:-} -cp "${APP_CLASSPATH:-}" -jar "${APP_JAR}" "$@"
    fi
fi

if [ "${TOOL:-docx}" = "pdf" ]; then
    exec java ${JAVA_OPTS:-} -cp /app/app.jar com.example.converter.UrlToPdf "$@"
fi

exec java ${JAVA_OPTS:-} -cp /app/app.jar com.example.converter.UrlToDocx "$@"

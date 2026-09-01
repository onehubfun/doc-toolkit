#!/usr/bin/env node
/**
 * rs-fetch.js —— 瑞数(RuiShu) VMP 防护网站的通用抓取工具
 *
 * 背景：
 *   部分网站（本项目实测过陕西省税务局官网）用瑞数的 VMP 反爬网关保护，首次请求
 *   会返回 412/一段高度混淆的 JS 挑战代码，浏览器（不管是标准 Chromium、还是
 *   Camoufox / CloakBrowser 这类"伪装指纹"的反检测浏览器）执行这段 JS 后虽然能
 *   生成一个 cookie，但拿着这个 cookie 重新发起请求依然会被拒绝（400）——因为
 *   瑞数把 cookie 和生成它的那条具体连接/客户端指纹绑定住了，换一个浏览器/连接
 *   复用 cookie 没用。
 *
 *   sdenv（https://github.com/pysunday/sdenv）这个"补环境框架"不启动真实浏览器，
 *   而是用改造过的 jsdom 在 Node.js 里模拟浏览器环境，直接执行瑞数的验证 JS，
 *   并且**在同一个 Node fetch 客户端里**用算出来的 cookie 发起验证请求——这样
 *   cookie 和发请求的连接从头到尾是同一个，验证能通过，从而拿到完整的、明文的
 *   真实页面 HTML。
 *
 * 这个脚本做的事：
 *   输入一个 URL，判断是不是瑞数保护的网站：
 *     - 不是瑞数网站：不消费这段逻辑，直接退出并告知调用方"按正常流程处理"，
 *       不代替 Playwright 做普通页面的抓取（sdenv 的 jsdom 不执行完整的现代
 *       前端框架渲染，用它抓普通页面反而会不如 Playwright + Chromium）。
 *     - 是瑞数网站：走"生成 cookie -> 用同一个连接验证 -> 拿到完整 HTML"这条
 *       路，把 HTML 写到指定文件，供调用方（比如 Java 里的 Playwright）用
 *       `page.setContent()` 本地注入渲染、再打印 PDF —— 这一步不会再向瑞数
 *       服务器发起新的、会被拦截的"文档级"请求。
 *
 * 用法：
 *   node rs-fetch.js <url> <output-html-file> [--timeout=30000] [--ua="..."]
 *
 * 退出码：
 *   0  成功拿到内容，HTML 已写入 output-html-file，stdout 最后一行是结果 JSON
 *   2  这不是一个瑞数保护的网站，调用方应该走自己正常的抓取流程（不算错误）
 *   1  是瑞数网站，但抓取失败（超时/验证不通过/网络错误等），stdout 最后一行
 *      是错误 JSON
 *
 * stdout 输出约定：
 *   过程中的调试信息全部走 stderr；stdout 只在结束时打印**唯一一行** JSON，
 *   方便调用方（不想引入完整 JSON 流解析器时）直接读最后一行文本解析。
 *   成功: {"ok":true,"isRuiShu":true,"title":"...","htmlPath":"...","htmlLength":123,"elapsedMs":456}
 *   非瑞数: {"ok":false,"isRuiShu":false,"reason":"not_rs_site"}
 *   失败: {"ok":false,"isRuiShu":true,"reason":"...","message":"..."}
 */

'use strict';

process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
process.env.OPENSSL_LEGACY_RENEGOTIATION = '1';

const fs = require('fs');
const path = require('path');

const DEFAULT_UA =
    'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 ' +
    '(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36';

function printResultAndExit(exitCode, resultObj) {
    // 唯一约定：stdout 最后一行必须是这一条 JSON，调试信息一律走 stderr。
    process.stdout.write(JSON.stringify(resultObj) + '\n');
    process.exit(exitCode);
}

function log(...args) {
    console.error('[rs-fetch]', ...args);
}

function parseArgs(argv) {
    const positional = [];
    const opts = { timeout: 30000, ua: DEFAULT_UA };
    for (const arg of argv) {
        if (arg.startsWith('--timeout=')) {
            const v = parseInt(arg.slice('--timeout='.length), 10);
            if (!Number.isNaN(v) && v > 0) opts.timeout = v;
        } else if (arg.startsWith('--ua=')) {
            opts.ua = arg.slice('--ua='.length);
        } else {
            positional.push(arg);
        }
    }
    opts.url = positional[0];
    opts.outputHtmlFile = positional[1];
    return opts;
}

function extractTitle(html) {
    const titleMatch = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
    if (titleMatch && titleMatch[1].trim()) return titleMatch[1].trim();
    const descMatch = html.match(/<meta[^>]+name=["']description["'][^>]+content=["']([^"']*)["']/i);
    if (descMatch && descMatch[1].trim()) return descMatch[1].trim();
    return '';
}

async function main() {
    const opts = parseArgs(process.argv.slice(2));

    if (!opts.url) {
        console.error('用法: node rs-fetch.js <url> <output-html-file> [--timeout=30000] [--ua="..."]');
        printResultAndExit(1, { ok: false, isRuiShu: false, reason: 'missing_url' });
        return;
    }
    if (!opts.outputHtmlFile) {
        console.error('缺少 output-html-file 参数');
        printResultAndExit(1, { ok: false, isRuiShu: false, reason: 'missing_output_path' });
        return;
    }

    // sdenv 是全局 npm 包安装在镜像里的，不一定在当前脚本目录的 node_modules 链路上，
    // 显式加一次 NODE_PATH 保底（Dockerfile 里也会设置，这里是双保险，方便脚本单独调试）。
    if (!process.env.NODE_PATH) {
        process.env.NODE_PATH = '/usr/local/lib/node_modules';
        require('module').Module._initPaths();
    }

    let jsdomFromUrl;
    try {
        ({ jsdomFromUrl } = require('sdenv'));
    } catch (err) {
        console.error('加载 sdenv 失败，请确认镜像里已经安装 sdenv 并且 NODE_PATH 正确指向它：', err.message);
        printResultAndExit(1, { ok: false, isRuiShu: false, reason: 'sdenv_not_installed', message: err.message });
        return;
    }

    const startedAt = Date.now();
    const timeoutMs = opts.timeout;

    // 整体超时保护：不管 sdenv 内部卡在哪一步（生成 cookie、等待跳转事件、发起验证
    // 请求），到时间就统一判失败退出，不要让容器/上层调用方无限期挂起。
    let timedOut = false;
    const timeoutTimer = setTimeout(() => {
        timedOut = true;
        console.error(`整体超时(${timeoutMs}ms)，判定为失败`);
        printResultAndExit(1, {
            ok: false,
            isRuiShu: true,
            reason: 'timeout',
            message: `超过 ${timeoutMs}ms 未完成`,
        });
    }, timeoutMs);
    // 不要因为这个定时器阻止进程退出（正常完成时会走 process.exit，无所谓，
    // 但加上 unref 更规范，避免脚本被挂起等这个 timer）。
    timeoutTimer.unref();

    let dom;
    try {
        dom = await jsdomFromUrl(opts.url, {
            userAgent: opts.ua,
            consoleConfig: { error: new Function() },
        });
    } catch (err) {
        if (timedOut) return; // 超时分支已经处理并退出过了
        clearTimeout(timeoutTimer);
        log('首次请求失败：', err.message);
        printResultAndExit(1, { ok: false, isRuiShu: false, reason: 'initial_request_failed', message: err.message });
        return;
    }

    if (timedOut) return;

    // window.$_ts 是瑞数 VMP 挑战脚本的特征全局变量，没有它就说明这不是瑞数保护
    // 的网站——按约定退出码 2，让调用方自己决定用正常流程（比如直接走 Playwright）
    // 处理，不要在这里越俎代庖。
    if (!dom.window.$_ts) {
        clearTimeout(timeoutTimer);
        log('未检测到瑞数特征($_ts)，判定为非瑞数网站');
        printResultAndExit(2, { ok: false, isRuiShu: false, reason: 'not_rs_site' });
        return;
    }

    log('检测到瑞数网站，等待生成 cookie 并发起验证请求...');

    try {
        await new Promise((resolve, reject) => {
            dom.window.addEventListener('sdenv:exit', async (e) => {
                try {
                    if (timedOut) return;
                    if (!['location.replace', 'location.assign'].includes(e.detail.eventId)) {
                        return; // 不是我们关心的跳转/验证事件，继续等
                    }

                    const cookies = dom.cookieJar.getCookieStringSync(opts.url);
                    log('已生成 cookie（长度 ' + cookies.length + '），准备用同一个连接验证...');
                    dom.window.close();

                    const res = await fetch(e.detail.url, {
                        headers: { Cookie: cookies, 'user-agent': opts.ua },
                    });
                    log('验证请求状态码：', res.status);

                    if (res.status !== 200) {
                        reject(new Error(`验证请求返回非 200 状态码: ${res.status}`));
                        return;
                    }

                    const contentType = res.headers.get('content-type') || '';
                    const text = await res.text();

                    if (contentType.includes('application/json')) {
                        // 少数接口场景下瑞数验证通过后直接返回 JSON 数据，不是 HTML 页面，
                        // 原样写文件，调用方自己按 JSON 处理。
                        log('验证通过，返回内容是 JSON，不是 HTML');
                    }

                    const parent = path.dirname(opts.outputHtmlFile);
                    if (parent && !fs.existsSync(parent)) {
                        fs.mkdirSync(parent, { recursive: true });
                    }
                    fs.writeFileSync(opts.outputHtmlFile, text, 'utf-8');

                    const title = extractTitle(text);
                    log('验证通过，已写入文件，标题：', title || '(未提取到标题)');
                    resolve({ title, length: text.length });
                } catch (err) {
                    reject(err);
                }
            });
        }).then(({ title, length }) => {
            if (timedOut) return;
            clearTimeout(timeoutTimer);
            printResultAndExit(0, {
                ok: true,
                isRuiShu: true,
                title,
                htmlPath: opts.outputHtmlFile,
                htmlLength: length,
                elapsedMs: Date.now() - startedAt,
            });
        });
    } catch (err) {
        if (timedOut) return;
        clearTimeout(timeoutTimer);
        log('验证流程失败：', err.message);
        printResultAndExit(1, { ok: false, isRuiShu: true, reason: 'verification_failed', message: err.message });
    }
}

main().catch((err) => {
    console.error('未捕获的异常：', err);
    printResultAndExit(1, { ok: false, isRuiShu: false, reason: 'uncaught_exception', message: err.message });
});

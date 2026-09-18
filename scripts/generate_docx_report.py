#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Generate a professional, well-formatted Microsoft Word (.docx) Architecture Report
for the Ledger App project without external dependencies.
"""

import os
import zipfile
import html

def escape_xml(text):
    if text is None:
        return ""
    return html.escape(str(text), quote=False).replace('"', '&quot;').replace("'", '&apos;')

def build_p(text="", style="Normal", bold=False, italic=False, color=None, size=None, align=None, space_before=None, space_after=None):
    p_pr = []
    if style != "Normal":
        p_pr.append(f'<w:pStyle w:val="{style}"/>')
    if align:
        p_pr.append(f'<w:jc w:val="{align}"/>')
    
    sp_attrs = []
    if space_before is not None:
        sp_attrs.append(f'w:before="{space_before}"')
    if space_after is not None:
        sp_attrs.append(f'w:after="{space_after}"')
    if sp_attrs:
        p_pr.append(f'<w:spacing {" ".join(sp_attrs)} w:line="276" w:lineRule="auto"/>')
    
    p_pr_xml = f"<w:pPr>{''.join(p_pr)}</w:pPr>" if p_pr else ""

    r_pr = []
    if bold:
        r_pr.append("<w:b/>")
    if italic:
        r_pr.append("<w:i/>")
    if color:
        r_pr.append(f'<w:color w:val="{color}"/>')
    if size:
        r_pr.append(f'<w:sz w:val="{size}"/>')
    
    r_pr_xml = f"<w:rPr>{''.join(r_pr)}</w:rPr>" if r_pr else ""
    t_xml = f"<w:t xml:space=\"preserve\">{escape_xml(text)}</w:t>"

    return f"<w:p>{p_pr_xml}<w:r>{r_pr_xml}{t_xml}</w:r></w:p>"

def build_bullet(text="", bold_prefix="", level=0):
    p_pr = f'<w:pPr><w:pStyle w:val="ListParagraph"/><w:numPr><w:ilvl w:val="{level}"/><w:numId w:val="1"/></w:numPr><w:spacing w:after="80" w:line="260" w:lineRule="auto"/></w:pPr>'
    runs = []
    if bold_prefix:
        runs.append(f'<w:r><w:rPr><w:b/><w:color w:val="1E293B"/></w:rPr><w:t xml:space="preserve">{escape_xml(bold_prefix)} </w:t></w:r>')
    runs.append(f'<w:r><w:rPr><w:color w:val="334155"/></w:rPr><w:t xml:space="preserve">{escape_xml(text)}</w:t></w:r>')
    return f"<w:p>{p_pr}{''.join(runs)}</w:p>"

def build_callout(title, text):
    p_title = (
        f'<w:p>'
        f'<w:pPr><w:pBdr><w:left w:val="single" w:sz="24" w:space="12" w:color="0284C7"/></w:pBdr>'
        f'<w:shd w:val="clear" w:color="auto" w:fill="F0F9FF"/>'
        f'<w:spacing w:before="120" w:after="40"/>'
        f'<w:ind w:left="240" w:right="120"/>'
        f'</w:pPr>'
        f'<w:r><w:rPr><w:b/><w:color w:val="0369A1"/><w:sz w:val="22"/></w:rPr>'
        f'<w:t xml:space="preserve">{escape_xml(title)}</w:t></w:r>'
        f'</w:p>'
    )
    p_text = (
        f'<w:p>'
        f'<w:pPr><w:pBdr><w:left w:val="single" w:sz="24" w:space="12" w:color="0284C7"/></w:pBdr>'
        f'<w:shd w:val="clear" w:color="auto" w:fill="F0F9FF"/>'
        f'<w:spacing w:after="160" w:line="260" w:lineRule="auto"/>'
        f'<w:ind w:left="240" w:right="120"/>'
        f'</w:pPr>'
        f'<w:r><w:rPr><w:color w:val="334155"/><w:sz w:val="20"/></w:rPr>'
        f'<w:t xml:space="preserve">{escape_xml(text)}</w:t></w:r>'
        f'</w:p>'
    )
    return p_title + p_text

def build_table(headers, rows):
    col_count = len(headers)
    grid_xml = "".join(["<w:gridCol w:w=\"2400\"/>" for _ in range(col_count)])
    
    tbl_pr = (
        '<w:tblPr>'
        '<w:tblStyle w:val="TableGrid"/>'
        '<w:tblW w:w="0" w:type="auto"/>'
        '<w:tblBorders>'
        '<w:top w:val="single" w:sz="8" w:space="0" w:color="CBD5E1"/>'
        '<w:left w:val="single" w:sz="8" w:space="0" w:color="CBD5E1"/>'
        '<w:bottom w:val="single" w:sz="8" w:space="0" w:color="CBD5E1"/>'
        '<w:right w:val="single" w:sz="8" w:space="0" w:color="CBD5E1"/>'
        '<w:insideH w:val="single" w:sz="4" w:space="0" w:color="E2E8F0"/>'
        '<w:insideV w:val="single" w:sz="4" w:space="0" w:color="E2E8F0"/>'
        '</w:tblBorders>'
        '<w:tblCellMar><w:top w:w="120" w:type="dxa"/><w:bottom w:w="120" w:type="dxa"/><w:left w:w="160" w:type="dxa"/><w:right w:w="160" w:type="dxa"/></w:tblCellMar>'
        '</w:tblPr>'
    )

    header_cells = []
    for h in headers:
        c_xml = (
            f'<w:tc>'
            f'<w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="0F172A"/></w:tcPr>'
            f'<w:p><w:pPr><w:spacing w:before="60" w:after="60"/></w:pPr>'
            f'<w:r><w:rPr><w:b/><w:color w:val="FFFFFF"/><w:sz w:val="20"/></w:rPr>'
            f'<w:t xml:space="preserve">{escape_xml(h)}</w:t></w:r>'
            f'</w:p>'
            f'</w:tc>'
        )
        header_cells.append(c_xml)
    header_tr = f'<w:tr><w:trPr><w:tblHeader/></w:trPr>{"".join(header_cells)}</w:tr>'

    body_trs = []
    for r_idx, r in enumerate(rows):
        fill_color = "F8FAFC" if r_idx % 2 == 1 else "FFFFFF"
        cells = []
        for c in r:
            c_xml = (
                f'<w:tc>'
                f'<w:tcPr><w:shd w:val="clear" w:color="auto" w:fill="{fill_color}"/></w:tcPr>'
                f'<w:p><w:pPr><w:spacing w:before="40" w:after="40"/><w:line w:line="240" w:lineRule="auto"/></w:pPr>'
                f'<w:r><w:rPr><w:color w:val="1E293B"/><w:sz w:val="19"/></w:rPr>'
                f'<w:t xml:space="preserve">{escape_xml(c)}</w:t></w:r>'
                f'</w:p>'
                f'</w:tc>'
            )
            cells.append(c_xml)
        body_trs.append(f'<w:tr>{"".join(cells)}</w:tr>')

    return f'<w:tbl>{tbl_pr}<w:tblGrid>{grid_xml}</w:tblGrid>{header_tr}{"".join(body_trs)}</w:tbl>'

def generate_report_docx(output_path):
    os.makedirs(os.path.dirname(os.path.abspath(output_path)), exist_ok=True)
    body_elements = []

    # Cover / Header
    body_elements.append(build_p("LEDGER APP", style="Title", bold=True, color="0F172A", size="52", align="center", space_before=300, space_after=100))
    body_elements.append(build_p("智能全功能个人记账与财务分析系统 — 全景技术架构设计与系统工程报告", style="Subtitle", bold=True, color="0369A1", size="26", align="center", space_after=200))
    body_elements.append(build_p("Comprehensive System Architecture & Engineering Report", italic=True, color="64748B", size="20", align="center", space_after=400))
    
    body_elements.append(build_table(
        ["项目元数据", "配置 / 规范说明"],
        [
            ["系统名称", "Ledger App (Web + Android Companion)"],
            ["架构模式", "分层架构 (Layered Architecture) + 策略模式 (Strategy Pattern) + 薄控制器"],
            ["后端核心", "Python 3.12 + Flask 3.0 + Gunicorn 多 Worker 并发"],
            ["数据库层", "双模引擎：本地 SQLite WAL / Turso Cloud (libsql-client 分布式云库)"],
            ["前端体系", "原生响应式现代 SPA 体验 + Chart.js 4.x + Vanilla CSS 极致性能"],
            ["移动端伴侣", "Android Native (Kotlin + Jetpack + NotificationListenerService)"],
            ["AI & 视觉", "RapidOCR (ONNX Runtime) + 启发式自然语言解析 (NLP Smart Parser)"],
            ["国际化支持", "简体中文 (zh)、English (en)、Bahasa Melayu (ms)、繁体中文 (zh_TW)"],
            ["工程规范", "AGENTS.md 本地门禁 (Flake8 0-error + Pytest 100% 通过即提)"]
        ]
    ))
    body_elements.append(build_p("", space_after=300))

    # Chapter 1
    body_elements.append(build_p("1. 项目概述与业务愿景 (Executive Summary)", style="Heading1", bold=True, color="0F172A", size="32", space_before=300, space_after=120))
    body_elements.append(build_p(
        "Ledger App 是一套为现代个人及家庭量身打造的跨平台、高隐私、自动化记账与深度财务洞察系统。"
        "区别于市面上充斥广告、强制联网云端绑定、数据泄露隐患重重的商业记账软件，本系统秉承“数据自主所有权（Self-Hosted & Privacy-First）”"
        "与“零繁琐自动化记账”两大核心愿景，实现了覆盖从移动端通知监听捕获、小票图像拍照 OCR 智能拆单分账、自然语言秒级录入，"
        "到预算限额监控、固定账单订阅、债务还款规划及多维度交互式数据可视化的全生命周期闭环。",
        color="334155", size="21", space_after=140
    ))
    body_elements.append(build_bullet("无感自动化：依托 Android Companion 后台通知无感监听主流银行与电子钱包支付通知，秒级自动入账。", bold_prefix="• 全渠道捕获:"))
    body_elements.append(build_bullet("独创防自转混淆与防自借还款误判的滑动时间窗口检测机制，杜绝账户间对调产生的虚假支出。", bold_prefix="• 智能抗干扰:"))
    body_elements.append(build_bullet("内置 RapidOCR 离线推理引擎，具备 0°/90°/180°/270° 自适应朝向校正与品名税率拆分分摊算法。", bold_prefix="• 小票 AA 分账:"))
    body_elements.append(build_bullet("支持朋友还款一键抵扣原消费记录，实时联动更新净支出与结余，彻底解决代付垫资账目混乱顽疾。", bold_prefix="• 支出冲抵闭环:"))

    # Chapter 2
    body_elements.append(build_p("2. 系统分层架构与拓扑 (System Topology)", style="Heading1", bold=True, color="0F172A", size="32", space_before=300, space_after=120))
    body_elements.append(build_p(
        "系统严格遵守现代软件工程的分层解耦原则与 AGENTS.md 规定的架构防腐门禁，整体拓扑划分为五个高内聚、低耦合的核心层次：",
        color="334155", size="21", space_after=120
    ))
    body_elements.append(build_table(
        ["架构层次", "关键组件与目录", "核心职责与设计原则"],
        [
            ["表现层 (Presentation)", "templates/, static/ (app.js, styles.css)", "遵循薄前端高性能原则，不引入臃肿重型框架；基于 Chart.js 呈现沉浸式图表；全面支持深浅色与隐私脱敏模式。"],
            ["控制装配层 (Controllers)", "app.py, blueprints/", "遵循薄控制器 (Thin Controller) 原则；app.py 仅作应用装配与中间件注册；各 Blueprint 仅负责 HTTP 校验、调用服务与响应包装。"],
            ["领域服务层 (Domain Services)", "services/ (parsers, ocr, ai, notification)", "承载复杂核心业务规则。严禁依赖 Flask 上下文，纯函数化设计与策略模式，具备 100% 独立可测试性。"],
            ["核心基础设施 (Core & Infra)", "core/ (db, config, i18n, utils)", "提供多语言国际化字典引擎、统一数据库连接管理、CSRF 安全防护与全局元数据持久化支持。"],
            ["移动端伴侣 (Mobile Companion)", "android-companion/ (Kotlin/Jetpack)", "原生 Android 伴侣端，常驻后台拦截银行/钱包通知，执行初步过滤并通过安全 Token 投递至后端 API。"]
        ]
    ))
    body_elements.append(build_p("", space_after=200))

    # Chapter 3
    body_elements.append(build_p("3. 后端模块化与业务蓝图详解 (Backend Modules)", style="Heading1", bold=True, color="0F172A", size="32", space_before=300, space_after=120))
    body_elements.append(build_p(
        "后端采用 Flask Blueprint 进行横向业务领域切分，彻底杜绝了将业务堆砌在单一入口的单体膨胀风险：",
        color="334155", size="21", space_after=120
    ))
    body_elements.append(build_bullet("负责流水记账增删改查、批量操作、5 秒撤销恢复窗口 (Undo Toast)、分类限额阈值计算、Excel 导入导出与借还冲抵。", bold_prefix="1. transactions (交易核心):"))
    body_elements.append(build_bullet("驱动总体概览 (Overview) 宏观统计、长期月度收支趋势柱状图、主副业收入结构比、分类占比动态自适应环形图以及深度洞察钻取。", bold_prefix="2. analytics (统计分析):"))
    body_elements.append(build_bullet("接收移动端伴侣推送的银行原始交易通知，调度策略解析器执行特征匹配，触发自动化入账与实时去重。", bold_prefix="3. auto_track (自动记账):"))
    body_elements.append(build_bullet("集成 RapidOCR 推理、小票多角度纠偏、单品明细识别、服务费/SST 税费智能摊派与人头均摊计算器。", bold_prefix="4. split_bill (AA分账):"))
    body_elements.append(build_bullet("周期性固定支出与收入调度，支持按周、月、季、年等周期自动推算扣费日并预警支出节奏。", bold_prefix="5. subscriptions (固定账单):"))
    body_elements.append(build_bullet("跟进分期贷款、信用卡账单、借款债务，支持录入年化利息、还款计划表并核算净负债。", bold_prefix="6. liabilities (资产负债):"))
    body_elements.append(build_bullet("系统全局配置中心，包括货币代码、主副业标签定义、自然语言免确认开关、时区探测与安全令牌管理。", bold_prefix="7. settings (个性化设置):"))
    body_elements.append(build_bullet("基于会话 Session 与 HTTP-Only 保护的轻量安全鉴权，支持多用户环境下的租户数据隔离。", bold_prefix="8. auth (安全认证):"))

    # Chapter 4
    body_elements.append(build_p("4. 开放封闭策略解析器引擎 (Strategy Pattern)", style="Heading1", bold=True, color="0F172A", size="32", space_before=300, space_after=120))
    body_elements.append(build_p(
        "在解析各家银行、电子钱包短信与推送通知时，传统项目常使用嵌套巨型 if/elif 结构，极易导致新旧规则冲突与难以维护。"
        "Ledger App 强制采用策略模式 (Strategy Pattern)，所有渠道解析器均继承自 NotificationParserStrategy 抽象基类，并采用 @register_parser 自动注册。",
        color="334155", size="21", space_after=120
    ))
    body_elements.append(build_callout(
        "开闭原则 (Open/Closed Principle) 强制规范",
        "所有新增银行渠道（如 Public Bank、RHB、CIMB）必须在 services/parsers/ 下新建独立策略类，禁止修改既有解析流程；每个策略类必须配备独立的纯文本单元测试（test_parsers_strategy.py），实现零副作用扩展。"
    ))
    body_elements.append(build_p("当前已内置生产级解析策略：", color="1E293B", bold=True, space_before=100, space_after=80))
    body_elements.append(build_bullet("解析 DuitNow 扫码、P2P 转账、商家付款与余额充值，精准提取商户名称与流水号。", bold_prefix="• Touch 'n Go eWallet:"))
    body_elements.append(build_bullet("提取 GrabFood、GrabCar、GrabPay 二维码转账与 GrabRewards 抵扣后真实出账金额。", bold_prefix="• Grab / GrabPay:"))
    body_elements.append(build_bullet("解析 MAE App 推送、DuitNow Transfer、支出与来账通知，精准提取付款方与收款方账户尾号。", bold_prefix="• Maybank / MAE:"))
    body_elements.append(build_bullet("通用银行借记卡/信用卡消费短信策略，支持各大主流发卡行标准 SMS 模版识别。", bold_prefix="• Standard Bank SMS:"))

    # Chapter 5
    body_elements.append(build_p("5. 小票 RapidOCR 与图像自适应校正 (OCR & Computer Vision)", style="Heading1", bold=True, color="0F172A", size="32", space_before=300, space_after=120))
    body_elements.append(build_p(
        "小票扫描与 AA 分账模块是 Ledger App 的特色硬核工程。面对用户拍照角度倾斜、小票横置甚至倒置的常见现实痛点，系统构建了自适应视觉处理管线：",
        color="334155", size="21", space_after=120
    ))
    body_elements.append(build_bullet("用户上传小票图片后，预处理管线执行灰度化、高斯自适应对比度增强与双边滤波去噪，显著提升折痕小票文字对比度。", bold_prefix="1. 图像预处理:"))
    body_elements.append(build_bullet("基于文字检出框长宽比与置信度特征，自动探测小票是否存在 90°、180° 或 270° 偏转，并触发无损旋转校正。", bold_prefix="2. 智能多向纠偏:"))
    body_elements.append(build_bullet("采用轻量化 ONNX 运行时的 RapidOCR 模型，在无需 GPU 的普通云服务器或轻量 VPS 上即可实现 300ms 极速高精文本提取。", bold_prefix="3. 离线模型推理:"))
    body_elements.append(build_bullet("基于正则与空间拓扑距离算法，智能分离菜品名称、单价、数量、10% 服务费 (Service Charge) 与 6% SST 税费，并提供可交互的人头点选分账矩阵。", bold_prefix="4. 智能要素提取:"))

    # Chapter 6
    body_elements.append(build_p("6. Android 移动端伴侣架构 (Android Companion)", style="Heading1", bold=True, color="0F172A", size="32", space_before=300, space_after=120))
    body_elements.append(build_p(
        "Android Companion 位于 android-companion/ 目录，采用现代 Kotlin + Jetpack 架构：",
        color="334155", size="21", space_after=120
    ))
    body_elements.append(build_bullet("继承自 Android 原生 NotificationListenerService，在获得系统通知访问权限后常驻后台，毫秒级响应系统广播推送。", bold_prefix="• 常驻无感监听:"))
    body_elements.append(build_bullet("维护应用白名单（包名过滤），杜绝无关应用通知唤醒，保持极低耗电与零隐私侵扰。", bold_prefix="• 白名单过滤:"))
    body_elements.append(build_bullet("每次推送携带用户在设置中生成的专属 API Sync Token，通过 HTTPS POST 协议加密发送至服务端 /api/auto-track/push。", bold_prefix="• 安全网络同步:"))
    body_elements.append(build_bullet("配套 GitHub Actions CI/CD 流水线，每次功能合并自动编译生成最新 release/debug APK 产物。", bold_prefix="• 自动化 APK 构建:"))

    # Chapter 7
    body_elements.append(build_p("7. 数据持久化、并发安全与多 Worker 状态防护 (Data & Security)", style="Heading1", bold=True, color="0F172A", size="32", space_before=300, space_after=120))
    body_elements.append(build_p(
        "针对生产环境下多进程容器部署与高并发写入，系统设计了严密的持久化与安全防护体系：",
        color="334155", size="21", space_after=120
    ))
    body_elements.append(build_bullet("本地模式下采用 WAL (Write-Ahead Logging) 模式，极大提升读写并发性能，避免 database is locked 错误；云端模式支持无缝切换至 Turso (libsql-client)，实现跨区域边缘低延迟云同步。", bold_prefix="• 双模数据库适配:"))
    body_elements.append(build_bullet("严禁使用 Python 进程内存全局变量保存关键数据版本！通过 system_metadata 表（bump_data_version 与 get_data_version）持久化版本标记，保证 Gunicorn 多 Worker 架构下前端局部刷新与轮询状态 100% 同步一致。", bold_prefix="• 多进程数据版本一致性:"))
    body_elements.append(build_bullet("全站集成 Flask-WTF CSRF 防护，所有 POST/PUT/DELETE 请求与 AJAX 异步提交均校验 CSRF Token；敏感端点设置防爆破频次限制；会话启用 HttpOnly 与 SameSite 保护。", bold_prefix="• 深度安全防护:"))

    # Chapter 8
    body_elements.append(build_p("8. 前端设计哲学、自适应防遮挡与全国际化 (Frontend & UI)", style="Heading1", bold=True, color="0F172A", size="32", space_before=300, space_after=120))
    body_elements.append(build_p(
        "前端遵循现代简约设计语言与最高级别审美标准，具备如下核心特性：",
        color="334155", size="21", space_after=120
    ))
    body_elements.append(build_bullet("支持深色模式 (Dark Mode)、浅色模式与系统跟随，采用精心调配的高对比度柔和配色（HSL 调色系统），长时间使用眼睛舒适不疲劳。", bold_prefix="• 沉浸式双模主题:"))
    body_elements.append(build_bullet("提供一键隐私脱敏切换，即时将所有涉及账户余额、交易金额与图表中心数字模糊替换为 ••••••，在公共场合随时放心打开。", bold_prefix="• 隐私保护模式:"))
    body_elements.append(build_bullet("针对 Chart.js 环形图中心文本易被扇区切断的行业顽疾，实现了基于物理内径 (innerRadius) 的动态字号缩放算法，并采用 afterDraw 保证层级优先，杜绝图表遮挡内部文字。", bold_prefix="• 环形图中心文字自适应: "))
    body_elements.append(build_bullet("全站实现中、英、马、繁四语无死角国际化，所有后端模板、客户端 JS 动态渲染弹窗与图表文案均由 window.t 统一驱动。", bold_prefix="• 四语全面国际化:"))

    # Chapter 9
    body_elements.append(build_p("9. 质量保障体系与自动化测试门禁 (CI/CD Quality Gates)", style="Heading1", bold=True, color="0F172A", size="32", space_before=300, space_after=120))
    body_elements.append(build_p(
        "系统代码库严格推行现代敏捷与 DevOps 规范（AGENTS.md 准则）：",
        color="334155", size="21", space_after=120
    ))
    body_elements.append(build_bullet("任何提交前本地必须通过 flake8 严苛检查，严禁任何未定义变量、未引用模块或语法隐患。", bold_prefix="• 静态代码质量审查:"))
    body_elements.append(build_bullet("覆盖安全防范、数据库持久化、OCR 纠偏算法、策略解析器、AA 分账与多语言国际化等 104 项端到端单元测试与集成测试，确保 100% 通过。", bold_prefix="• 自动化回归测试套件:"))
    body_elements.append(build_bullet("每次准备提交前必须先执行 git pull --rebase，杜绝冲突覆盖与非快进报错；每个 Feature/Bugfix 必须配备清晰的规范提交信息（Conventional Commits）。", bold_prefix="• Git Rebase 提交流水线:"))

    # Chapter 10
    body_elements.append(build_p("10. 部署指南与未来演进路线 (Deployment & Roadmap)", style="Heading1", bold=True, color="0F172A", size="32", space_before=300, space_after=120))
    body_elements.append(build_bullet("内置多阶段安全 Dockerfile，一键在本地、VPS 或私有家庭 NAS (如群晖 Synology, Unraid) 上容器化拉起。", bold_prefix="• Docker 容器化:"))
    body_elements.append(build_bullet("内置 render.yaml 基础设施即代码配置，开箱即用支持 Render.com 云原生一键部署与持久化挂载卷配置。", bold_prefix="• Render 云部署:"))
    body_elements.append(build_bullet("未来版本将规划支持更多跨国银行 Open API 对接、家庭多成员协同账本与本地大模型 (Ollama) 私有化智能财务咨询报告生成。", bold_prefix="• 未来路线规划:"))

    body_elements.append(build_p("", space_after=400))
    body_elements.append(build_p("— 报告编制完成 · Ledger App Architecture Engineering Team —", italic=True, color="94A3B8", size="18", align="center"))

    # Assemble OpenXML files
    content_types_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">\n'
        '  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>\n'
        '  <Default Extension="xml" ContentType="application/xml"/>\n'
        '  <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>\n'
        '  <Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>\n'
        '</Types>'
    )

    pkg_rels_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\n'
        '  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>\n'
        '</Relationships>'
    )

    doc_rels_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">\n'
        '  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>\n'
        '</Relationships>'
    )

    styles_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">\n'
        '  <w:docDefaults>\n'
        '    <w:rPrDefault>\n'
        '      <w:rPr>\n'
        '        <w:rFonts w:ascii="Segoe UI" w:hAnsi="Segoe UI" w:eastAsia="Microsoft YaHei" w:cs="Segoe UI"/>\n'
        '        <w:sz w:val="21"/>\n'
        '        <w:color w:val="334155"/>\n'
        '      </w:rPr>\n'
        '    </w:rPrDefault>\n'
        '  </w:docDefaults>\n'
        '  <w:style w:type="paragraph" w:default="1" w:styleId="Normal">\n'
        '    <w:name w:val="Normal"/>\n'
        '    <w:pPr><w:spacing w:line="276" w:lineRule="auto"/></w:pPr>\n'
        '  </w:style>\n'
        '  <w:style w:type="paragraph" w:styleId="Heading1">\n'
        '    <w:name w:val="heading 1"/>\n'
        '    <w:pPr><w:spacing w:before="260" w:after="120" w:line="260" w:lineRule="auto"/><w:outlineLvl w:val="0"/></w:pPr>\n'
        '    <w:rPr><w:b/><w:color w:val="0F172A"/><w:sz w:val="32"/></w:rPr>\n'
        '  </w:style>\n'
        '  <w:style w:type="paragraph" w:styleId="Heading2">\n'
        '    <w:name w:val="heading 2"/>\n'
        '    <w:pPr><w:spacing w:before="180" w:after="80" w:line="260" w:lineRule="auto"/><w:outlineLvl w:val="1"/></w:pPr>\n'
        '    <w:rPr><w:b/><w:color w:val="0369A1"/><w:sz w:val="26"/></w:rPr>\n'
        '  </w:style>\n'
        '  <w:style w:type="paragraph" w:styleId="Heading3">\n'
        '    <w:name w:val="heading 3"/>\n'
        '    <w:pPr><w:spacing w:before="120" w:after="60" w:line="260" w:lineRule="auto"/><w:outlineLvl w:val="2"/></w:pPr>\n'
        '    <w:rPr><w:b/><w:color w:val="0F766E"/><w:sz w:val="22"/></w:rPr>\n'
        '  </w:style>\n'
        '  <w:style w:type="paragraph" w:styleId="Title">\n'
        '    <w:name w:val="Title"/>\n'
        '    <w:pPr><w:spacing w:before="200" w:after="100" w:line="280" w:lineRule="auto"/></w:pPr>\n'
        '    <w:rPr><w:b/><w:color w:val="0F172A"/><w:sz w:val="52"/></w:rPr>\n'
        '  </w:style>\n'
        '  <w:style w:type="paragraph" w:styleId="Subtitle">\n'
        '    <w:name w:val="Subtitle"/>\n'
        '    <w:pPr><w:spacing w:after="240" w:line="260" w:lineRule="auto"/></w:pPr>\n'
        '    <w:rPr><w:color w:val="64748B"/><w:sz w:val="24"/></w:rPr>\n'
        '  </w:style>\n'
        '</w:styles>'
    )

    doc_xml = (
        '<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n'
        '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">\n'
        '  <w:body>\n'
        f'    {"".join(body_elements)}\n'
        '    <w:sectPr>\n'
        '      <w:pgSz w:w="11906" w:h="16838"/>\n'
        '      <w:pgMar w:top="1440" w:right="1440" w:bottom="1440" w:left="1440"/>\n'
        '    </w:sectPr>\n'
        '  </w:body>\n'
        '</w:document>'
    )

    with zipfile.ZipFile(output_path, 'w', compression=zipfile.ZIP_DEFLATED) as z:
        z.writestr('[Content_Types].xml', content_types_xml)
        z.writestr('_rels/.rels', pkg_rels_xml)
        z.writestr('word/_rels/document.xml.rels', doc_rels_xml)
        z.writestr('word/styles.xml', styles_xml)
        z.writestr('word/document.xml', doc_xml)

    print(f"Successfully generated Word report: {output_path} ({os.path.getsize(output_path)} bytes)")

if __name__ == "__main__":
    report_file = os.path.join(os.path.dirname(__file__), "..", "docs", "Ledger_App_Architecture_Report.docx")
    generate_report_docx(report_file)

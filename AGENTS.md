# Workspace Workflow & Development Rules

所有在此代码库中进行的开发、修改和维护任务，必须严格遵守以下工作流与质量保障规则：

## 1. Feature 完成即提交与推送 (Commit & Push per Feature)
- 每当完成或更新一个功能特性（Feature）、漏洞修复（Bugfix）或优化时，必须完整进行版本管理，提交（Commit）并推送到 GitHub 远程仓库（`git push origin main`）。
- 提交信息（Commit Message）必须规范、清晰，推荐采用 Conventional Commits 格式（如 `feat(...)`, `fix(...)`, `test(...)`, `chore(...)`）。

## 2. 提交前必先拉取最新代码 (Pull Before Commit)
- 在任何准备 commit / push 之前，**必须先执行 `git pull --rebase`（或 `git pull`）** 获取远程仓库的最新提交。
- 原因说明：GitHub Actions CI 可能会自动将构建产物（如编译好的 Android APK）提交推送到远程仓库，先拉取最新代码能杜绝冲突、代码覆盖与非快进（non-fast-forward）错误。

## 3. 本地必跑 CI/CD 质量检查门禁 (Verify CI/CD Locally)
- 每次提交推送前，必须在本地执行并确保通过与 GitHub Actions 完全一致的检查流水线：
  1. **语法与代码质量检查**：
     `flake8 . --count --select=E9,F63,F7,F82,F401,F841 --show-source --statistics`
     （严禁引入未定义变量、未使用的 import 或致命语法错误）
  2. **自动化测试套件**：
     `pytest`
     （必须确保所有单元测试与集成测试 100% 通过）
- 只有本地验证全部通过后，方可执行 commit 与 push。

## 4. 交付前的全面自我审查 (Self-Review Before Handover)
- 每次完成工作、准备汇报给用户前，必须进行一次完整的复核与审查（Review）：
  - 通过 `git diff` 仔细逐行检查改动内容，确认改动精准、无遗留调试代码、无多余的临时脚本文件；
  - 检查边缘情况（Edge cases）与安全隐患（如 CSRF、会话生命周期、多端兼容性等）；
  - 确认代码符合预期目标且无破坏性副作用。

---

## 5. 架构分层与职责边界准则 (Architecture & Layering Rules)
- **严禁向 `app.py` 回填业务逻辑**：`app.py` 仅作为纯粹的应用装配入口（初始化 Flask、注册中间件与 Blueprints、配置反向代理与错误处理），核心业务逻辑与路由必须归入对应的模块（`blueprints/`、`services/`、`core/`）。
- **薄控制器原则 (Thin Controller)**：Blueprint 中的路由函数只负责 HTTP 参数解析、调用服务层与包装响应，禁止在视图函数中平铺长达数百行的复杂业务流程或原始 SQL 拼接。
- **显式依赖与可测试性 (Explicit Parameters)**：纯业务逻辑与计算函数禁止在内部隐式依赖 Flask 的 `session.get('user_id')` 或 `request` 上下文，必须通过显式形参（如 `user_id: int`, `db=None`）传入，保证脱离 Web 请求也能秒级编写单元测试。

## 6. 通知与解析开闭原则 (Open/Closed Strategy Pattern)
- **严禁使用巨型 `if/elif` 堆叠新渠道**：当新增或更新银行、电子钱包（如 Touch'n Go, Grab, Maybank, CIMB 等）的短信/推送解析规则时，**严禁在既有代码中直接写分支判断**。
- **强制策略化扩展**：必须在 `services/parsers/` 下独立实现 `NotificationParserStrategy` 策略类，并使用 `@register_parser` 装饰器实现自动注册。
- **策略必须配备单测**：每个新增策略必须在 `tests/test_parsers_strategy.py` 配齐纯文本的独立单元测试。

## 7. 状态持久化与多 Worker 安全准则 (State Persistence)
- **严禁使用单纯的 Python 进程内存全局变量保存关键状态**：为了防止在 Gunicorn 多 Worker（多进程）或容器重启时产生状态撕裂与前端局部更新失效，所有全局或用户级数据版本必须持久化到 `system_metadata` 表（通过 `bump_data_version` 与 `get_data_version`）。

## 8. 防御性编程与禁止静默吞咽异常 (No Silent Failures)
- **严禁裸露的 `except: pass` 或 `except Exception: pass`**：所有捕获的异常若需安全降级或忽略，必须带有明确的上下文日志（如 `logger.debug("...", exc_info=True)` 或 `logger.warning("...")`），杜绝因异常被静默吞掉导致的生产“幽灵故障”。


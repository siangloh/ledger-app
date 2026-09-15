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

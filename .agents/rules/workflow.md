# Workflow & CI/CD Development Rules

## 1. 提交前拉取最新代码 (Pull Before Commit)
- 每次准备 commit 之前，必须先执行 `git pull --rebase` 拉取远程最新提交（防止与 CI 自动发布的 APK 或协同改动冲突）。

## 2. 本地执行 CI/CD 质量验证 (Run CI/CD Checks)
- 提交前必须在本地验证：
  1. `flake8 . --count --select=E9,F63,F7,F82,F401,F841 --show-source --statistics`
  2. `pytest`
- 确保测试 100% 通过且无代码质量报警。

## 3. 功能完成即 Commit & Push (Commit & Push per Feature)
- 每个功能特性或修复完成后，必须执行 `git add`, `git commit` 并 `git push origin main`。

## 4. 完成后自我审查 (Self-Review)
- 每次交付前，通过 `git diff` 进行代码审查，确认无冗余文件、无副作用，并核实改动质量。

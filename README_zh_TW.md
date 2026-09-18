<div align="center">

# 💎 Ledger App
### 現代隱私優先 · 智能自動化個人記賬與財務分析系統

[![Tests](https://img.shields.io/badge/Tests-104%20passed-success?style=flat-square&logo=pytest)](file:///c:/Users/USER/Downloads/ledger-app/tests)
[![Python](https://img.shields.io/badge/Python-3.12+-blue?style=flat-square&logo=python)](https://www.python.org/)
[![Flask](https://img.shields.io/badge/Framework-Flask%203.0-lightgrey?style=flat-square&logo=flask)](https://palletsprojects.com/p/flask/)
[![Platform](https://img.shields.io/badge/Platform-Web%20%7C%20Android-green?style=flat-square&logo=android)](file:///c:/Users/USER/Downloads/ledger-app/android-companion)
[![Database](https://img.shields.io/badge/Database-SQLite%20%7C%20Turso%20Cloud-informational?style=flat-square&logo=sqlite)](https://turso.tech/)
[![Code Style](https://img.shields.io/badge/Code%20Style-Flake8%20Pass-success?style=flat-square)](https://flake8.pycqa.org/)

**語言選擇 / Languages / Pilihan Bahasa:**  
**[English](README.md)** · **[简体中文](README_zh.md)** · **[Bahasa Melayu](README_ms.md)** · **[繁體中文](README_zh_TW.md)**  
[架構工程報告 (Word .docx)](file:///c:/Users/USER/Downloads/ledger-app/LEDGER_APP_ARCHITECTURE_REPORT.docx) · [架構報告 (Markdown)](file:///c:/Users/USER/Downloads/ledger-app/docs/Ledger_App_Architecture_Report.md)

</div>

---

## 📖 項目簡介 (Overview)

**Ledger App** 是一套專為注重個人財務隱私、追求極簡高效與深度分析的用戶打造的全功能智能記賬系統。  
區別於傳統商業記賬軟件充斥廣告、強制聯網雲端、存在數據倒賣隱患的問題，本系統提供 **100% 數據自主掌控（Self-Hosted）**，並結合 **Android 原生後台監聽** 與 **本地離線 RapidOCR 圖像引擎**，實現了從出賬捕獲、智能分賬、預算監控到長週期宏觀分析的完整閉環。

---

## ✨ 核心特性矩陣 (Key Features)

| 模塊 | 功能亮點 | 架構與實現方案 |
| :--- | :--- | :--- |
| 📱 **無感自動記賬** | 監聽各大銀行、電子錢包支付通知並秒級自動錄入 | 基於 Android 原生 `NotificationListenerService`，配置應用白名單與防重放 API Token |
| 🛡️ **開閉策略解析器** | 針對 Touch 'n Go, Grab, Maybank 等渠道推行策略模式 | 採用策略模式 (`NotificationParserStrategy`) 與 `@register_parser` 自動註冊，杜絕巨型 `if/elif` |
| 🧾 **小票 AA 智能分賬** | 拍小票自動提取品名單價、稅率並一鍵多人分攤 | 基於輕量 RapidOCR ONNX 推理，獨創 0°/90°/180°/270° 自適應朝向糾偏與 SST/服務費拆分 |
| 🔄 **朋友還款支出衝抵** | 墊資聚餐後朋友還錢？一鍵衝抵原消費記錄 | 自動關聯原支出扣減實付金額，聯動重算結餘，具備滑動時間窗口防內部自轉誤判機制 |
| 📊 **交互式儀表盤** | 月度即時概覽 + 總體歷史宏觀趨勢圖 + 分類佔比 | 基於 Chart.js 4.x 構建，獨創基於物理內徑的環形圖中心文本自適應防遮擋算法 |
| 👁️ **隱私脫敏模式** | 在公共場合隨時打開，無需擔憂周圍視線 | 一鍵開啟隱私模式，所有賬戶餘額、圖表數字即時替換為高保真 `••••••` |
| 🌐 **四語無縫國際化** | 全站覆蓋中、英、馬、繁四種主流語言 | 由 `core/i18n.py` 驅動，前端與後端渲染無死角動態國際化 (`window.t`) |
| ⏰ **固定收支與債務** | 訂閱賬單提前預警、分期還款進度可視化 | 支持按周/月/季/年自動計算扣款日與年化利息負債總覽 |

---

## 🚀 快速開始 (Quick Start)

### 1. 本地環境運行 (Python 3.12+)

```bash
# 1. 克隆代碼倉庫
git clone https://github.com/siangloh/ledger-app.git
cd ledger-app

# 2. 創建並激活虛擬環境
python -m venv .venv

# Windows:
.venv\Scripts\activate
# Linux / macOS:
# source .venv/bin/activate

# 3. 安裝項目依賴
pip install -r requirements.txt

# 4. 啟動服務 (默認監聽 http://127.0.0.1:5000)
python app.py
```

### 2. Docker 容器化運行

```bash
# 構建 Docker 鏡像
docker build -t ledger-app .

# 啟動容器並掛載數據持久化卷
docker run -d \
  -p 5000:5000 \
  -v $(pwd)/data:/app/instance \
  --name my-ledger \
  ledger-app
```

---

## 📱 Android 伴侶端配置 (Android Companion)

1. 在 Android 手機上下載安裝最新生成的 APK（位於 [static/download/ledger-app.apk](file:///c:/Users/USER/Downloads/ledger-app/static/download/ledger-app.apk)）。
2. 在手機系統設置中授予 **通知使用權 (Notification Access)**。
3. 打開 Web 端系統設置頁面，生成專屬 **API Sync Token**。
4. 在 Android 伴侶端輸入服務地址（如 `https://your-ledger-domain.com`）與該 Token 即可全天候靜默同步！

---

## 🧪 自動化測試與質量門禁 (CI/CD Quality Gates)

本項目嚴格執行 [AGENTS.md](file:///c:/Users/USER/Downloads/ledger-app/AGENTS.md) 規定的代碼質量與測試門禁：

```bash
# 1. 靜態代碼質量審查 (嚴禁引入未定義變量、未用包與語法錯誤)
flake8 . --count --select=E9,F63,F7,F82,F401,F841 --show-source --statistics

# 2. 運行端到端自動化測試套件 (覆蓋 104 項單元測試與集成測試)
pytest
```

---

## 🌐 語言版本

- **English (英文)**: [README.md](README.md)
- **简体中文**: [README_zh.md](README_zh.md)
- **Bahasa Melayu (馬來語)**: [README_ms.md](README_ms.md)
- **繁體中文**: [README_zh_TW.md](README_zh_TW.md) (當前)

---

## 🛡️ 開源協議 (License)

本項目遵循 **MIT License** 開源授權。

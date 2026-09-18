<div align="center">

# 💎 Ledger App
### Sistem Pengurusan Kewangan & Perakaunan Peribadi Pintar, Automatik dan Mengutamakan Privasi

[![Tests](https://img.shields.io/badge/Tests-104%20passed-success?style=flat-square&logo=pytest)](file:///c:/Users/USER/Downloads/ledger-app/tests)
[![Python](https://img.shields.io/badge/Python-3.12+-blue?style=flat-square&logo=python)](https://www.python.org/)
[![Flask](https://img.shields.io/badge/Framework-Flask%203.0-lightgrey?style=flat-square&logo=flask)](https://palletsprojects.com/p/flask/)
[![Platform](https://img.shields.io/badge/Platform-Web%20%7C%20Android-green?style=flat-square&logo=android)](file:///c:/Users/USER/Downloads/ledger-app/android-companion)
[![Database](https://img.shields.io/badge/Database-SQLite%20%7C%20Turso%20Cloud-informational?style=flat-square&logo=sqlite)](https://turso.tech/)
[![Code Style](https://img.shields.io/badge/Code%20Style-Flake8%20Pass-success?style=flat-square)](https://flake8.pycqa.org/)

**Pilihan Bahasa / Languages / 语言选择:**  
**[English](README.md)** · **[简体中文](README_zh.md)** · **[Bahasa Melayu](README_ms.md)** · **[繁體中文](README_zh_TW.md)**  
[Laporan Seni Bina Kejuruteraan (Word .docx)](file:///c:/Users/USER/Downloads/ledger-app/LEDGER_APP_ARCHITECTURE_REPORT.docx) · [Laporan Seni Bina (Markdown)](file:///c:/Users/USER/Downloads/ledger-app/docs/Ledger_App_Architecture_Report.md)

</div>

---

## 📖 Gambaran Keseluruhan (Overview)

**Ledger App** ialah suite perakaunan peribadi dan analisis kewangan yang dibina khas untuk individu dan keluarga yang mengutamakan **privasi data**, **kemudahan automasi**, dan **analisis mendalam**.

Tidak seperti aplikasi komersial yang mengumpul data peribadi, memaparkan iklan, atau mengunci ciri-ciri di sebalik langganan awan berbayar, Ledger App adalah **100% dihoskan sendiri (Self-Hosted) dan selamat**. Dengan gabungan pendengar latar belakang asli Android dan enjin imbasan resit luar talian RapidOCR, ia menyediakan aliran kerja yang lancar dari penangkapan perbelanjaan hingga analisis jangka panjang.

---

## ✨ Ciri-Ciri Utama (Key Features)

| Modul | Keistimewaan | Pelaksanaan Teknikal |
| :--- | :--- | :--- |
| 📱 **Penjejakan Automatik** | Mengesan pemberitahuan pembayaran bank & e-dompet secara masa nyata | Perkhidmatan latar belakang asli Android `NotificationListenerService` dengan senarai putih aplikasi dan Token Penyegerakan API |
| 🛡️ **Enjin Penghurai Terbuka/Tertutup** | Penghurai strategi untuk Touch 'n Go, Grab, Maybank, dan kad bank | Corak Strategi (`NotificationParserStrategy`) dengan pendaftaran automatik `@register_parser` |
| 🧾 **Imbasan Resit Pintar (AA Split Bill)** | Tangkap foto resit untuk mengekstrak barang, cukai, dan bahagi bayaran | Enjin luar talian RapidOCR (ONNX Runtime), pembetulan orientasi automatik 4 arah (0°/90°/180°/270°), dan pembahagian SST/caj perkhidmatan |
| 🔄 **Tolakan Bayaran Balik Rakan** | Bayar dahulu makan malam dan rakan bayar balik? Tolak terus daripada perbelanjaan asal | Mengaitkan rekod masuk untuk mengurangkan jumlah bersih belanja sebenar, dilengkapi tetingkap masa anti-salah anggap pindahan sendiri |
| 📊 **Papan Pemuka Interaktif** | Ringkasan bulanan masa nyata + trend makro jangka panjang + pecahan kategori | Dibina dengan Chart.js 4.x, dengan algoritma penskalaan saiz fon automatik berdasarkan radius dalam fizikal (`innerRadius`) carta donat |
| 👁️ **Mod Privasi Awam** | Buka aplikasi dengan yakin di tempat awam atau pengangkutan awam | Suis satu klik menukar semua baki akaun dan angka carta kepada `••••••` |
| 🌐 **Antarabangsa 4 Bahasa** | Sokongan penuh Bahasa Inggeris, Bahasa Cina Ringkas, Bahasa Melayu, dan Bahasa Cina Tradisional | Didorong oleh `core/i18n.py`, menyatukan templat Jinja2, skrip JavaScript `window.t`, dan dialog amaran SweetAlert2 |
| ⏰ **Langganan & Liabiliti** | Amaran bil berulang, jadual pelunasan ansuran, dan penjejakan hutang | Penjadualan fleksibel (mingguan, bulanan, suku tahunan, tahunan), pengiraan kadar faedah, dan pelunasan pinjaman |

---

## 🚀 Panduan Permulaan Pantas (Quick Start)

### Keperluan Asas
- **Python 3.12+**
- Git

### 1. Pemasangan Pembangunan Tempatan

```bash
# 1. Klon repositori
git clone https://github.com/siangloh/ledger-app.git
cd ledger-app

# 2. Cipta dan aktifkan persekitaran maya Python
python -m venv .venv

# Windows:
.venv\Scripts\activate
# Linux / macOS:
# source .venv/bin/activate

# 3. Pasang kebergantungan
pip install -r requirements.txt

# 4. Jalankan pelayan pembangunan (Lalai ke http://127.0.0.1:5000)
python app.py
```

### 2. Pengerahan Kontena Docker

```bash
# Bina imej Docker
docker build -t ledger-app .

# Jalankan kontena dengan volum data berterusan
docker run -d \
  -p 5000:5000 \
  -v $(pwd)/data:/app/instance \
  --name ledger-app-container \
  ledger-app
```

---

## 📱 Konfigurasi Aplikasi Pendamping Android (Android Companion)

1. Muat turun APK terkini di [static/download/ledger-app.apk](file:///c:/Users/USER/Downloads/ledger-app/static/download/ledger-app.apk).
2. Pasang APK pada peranti Android anda dan berikan kebenaran **Akses Pemberitahuan (Notification Access)** dalam tetapan sistem.
3. Buka Ledger App Web, pergi ke **Tetapan (Settings)**, dan jana **API Sync Token** unik.
4. Masukkan URL pelayan anda (cth. `https://your-ledger-domain.com`) dan Token tersebut ke dalam aplikasi Android.
5. Pemberitahuan bank yang disokong kini akan disegerakkan secara automatik!

---

## 🧪 Ujian Automatik & Kawalan Kualiti (CI/CD)

Repositori ini mematuhi piawaian kawalan kualiti tempatan yang ditetapkan dalam [AGENTS.md](file:///c:/Users/USER/Downloads/ledger-app/AGENTS.md):

```bash
# 1. Semakan kualiti kod statik Flake8 (Sifar ralat dibenarkan)
flake8 . --count --select=E9,F63,F7,F82,F401,F841 --show-source --statistics

# 2. Jalankan ujian automatik Pytest (104 ujian unit dan integrasi)
pytest
```

---

## 🌐 Pilihan Bahasa

- **English (Bahasa Inggeris)**: [README.md](README.md)
- **简体中文 (Bahasa Cina Ringkas)**: [README_zh.md](README_zh.md)
- **Bahasa Melayu**: [README_ms.md](README_ms.md) (Semasa)
- **繁體中文 (Bahasa Cina Tradisional)**: [README_zh_TW.md](README_zh_TW.md)

---

## 📄 Lesen (License)

Projek ini dilesenkan di bawah **Lesen MIT**.

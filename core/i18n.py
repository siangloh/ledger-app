# -*- coding: utf-8 -*-
"""
多语言国际化 (i18n) 核心模块
支持:
  - 简体中文 (zh, 默认)
  - English (en)
  - Bahasa Melayu (ms)
  - 繁體中文 (zh_TW)

提供优雅降级（Fallback）、模板过滤器与运行时上下文解析。
"""

from flask import request, session, g

SUPPORTED_LANGUAGES = {
    'zh': {'code': 'zh', 'name': '简体中文', 'flag': '🇨🇳', 'label': '中文 (简)'},
    'en': {'code': 'en', 'name': 'English', 'flag': '🇺🇸', 'label': 'English'},
    'ms': {'code': 'ms', 'name': 'Bahasa Melayu', 'flag': '🇲🇾', 'label': 'Melayu'},
    'zh_TW': {'code': 'zh_TW', 'name': '繁體中文', 'flag': '🇭🇰', 'label': '中文 (繁)'},
}

DEFAULT_LOCALE = 'zh'

TRANSLATIONS = {
    'zh': {
        # 导航
        'nav.brand': '我的账本',
        'nav.dashboard': '仪表盘',
        'nav.records': '历史记录',
        'nav.split_bill': 'AA 分账',
        'nav.manage': '管理',
        'nav.accounts': '账户管理',
        'nav.liabilities': '负债分期',
        'nav.subscriptions': '订阅大厅',
        'nav.recurring': '固定收支',
        'nav.settings': '偏好设置',
        'nav.tools': '工具',
        'nav.insights': '分类洞察',
        'nav.categories': '分类管理',
        'nav.import': '批量导入',
        'nav.privacy': '隐私',
        'nav.privacy_title': '隐私遮罩 (快捷键: Ctrl+Shift+P)',
        'nav.logout': '登出',
        'nav.more': '更多',
        'nav.more_title': '更多管理功能',
        'nav.overview': '概览',
        'nav.details': '明细',
        'nav.language': '语言',
        'nav.install_pwa': '安装为手机应用',
        'nav.install_pwa_desc': '免开浏览器，享受全屏原生 App 体验',
        'nav.install_btn': '安装',
        'nav.close': '关闭',

        # 通用
        'common.save': '保存',
        'common.cancel': '取消',
        'common.delete': '删除',
        'common.edit': '编辑',
        'common.confirm': '确认',
        'common.add': '添加',
        'common.search': '搜索',
        'common.filter': '筛选',
        'common.all': '全部',
        'common.total': '总计',
        'common.subtotal': '单品小计',
        'common.amount': '金额',
        'common.date': '日期',
        'common.category': '分类',
        'common.account': '账户',
        'common.note': '备注',
        'common.type': '类型',
        'common.expense': '支出',
        'common.income': '收入',
        'common.transfer': '转账',
        'common.actions': '操作',
        'common.status': '状态',
        'common.success': '成功',
        'common.failed': '失败',
        'common.loading': '加载中...',
        'common.no_data': '暂无数据',
        'common.currency': '货币',
        'common.back': '返回',
        'common.view': '查看',

        # 仪表盘
        'dashboard.title': '财务概览',
        'dashboard.desc': '清晰掌控你的资产净值、每月收支与预算健康度。',
        'dashboard.current_month': '本月',
        'dashboard.monthly_expense': '本月支出',
        'dashboard.monthly_income': '本月收入',
        'dashboard.net_balance': '本月结余',
        'dashboard.savings_pool': '累计总储蓄',
        'dashboard.quick_add': '记一笔',
        'dashboard.quick_add_title': '快速记账',
        'dashboard.recent_transactions': '最近交易',
        'dashboard.budget': '预算',
        'dashboard.budget_used': '预算使用',
        'dashboard.view_all': '查看全部',
        'dashboard.charts': '收支趋势与分析',

        # 交易明细
        'records.title': '交易明细记录',
        'records.desc': '查看、筛选与追溯每一笔开销与进账。',
        'records.search_placeholder': '搜索备注、金额或分类...',
        'records.filter_type': '交易类型',
        'records.filter_category': '分类筛选',
        'records.filter_account': '账户筛选',
        'records.date_range': '日期范围',
        'records.export_csv': '导出 CSV',
        'records.no_records_hint': '暂无符合条件的交易记录',

        # 小票分账
        'split_bill.title': '小票拍照与智能 AA 分账',
        'split_bill.desc': '拍摄或上传小票，智能纠偏与聚合单品，秒算每个人应付多少！',
        'split_bill.step1': '第 1 步：录入小票信息',
        'split_bill.step2': '第 2 步：添加参与人 & 分配消费',
        'split_bill.step3': '第 3 步：分摊结果与一键催款',
        'split_bill.scan_single': '📸 拍摄单张小票',
        'split_bill.batch_upload': '🖼️ 批量选取多张 (多餐)',
        'split_bill.manual_paste': '或直接粘贴小票文本 / 明细：',
        'split_bill.grand_total': '小票实付总金额 (Grand Total)',
        'split_bill.service_charge': '服务费 / Tip',
        'split_bill.tax': '税费 (Tax/VAT)',
        'split_bill.members': '参与分账人员 (Members)',
        'split_bill.add_member': '+ 添加人员',
        'split_bill.copy_claim': '一键复制清单发群',
        'split_bill.save_to_ledger': '一键记入主账本',

        # 设置
        'settings.title': '个性化与偏好设置',
        'settings.desc': '定制视觉主题、默认货币、语言与系统习惯。',
        'settings.appearance': '视觉外观与排版',
        'settings.theme_mode': '主题模式',
        'settings.theme_system': '🖥️ 跟随系统',
        'settings.theme_dark': '🌙 暗黑沉浸',
        'settings.theme_light': '☀️ 明亮浅色',
        'settings.table_density': '明细表格密度',
        'settings.density_comfortable': '🛋️ 舒适模式 (推荐)',
        'settings.density_compact': '📑 紧凑高效模式',
        'settings.language_setting': '系统语言 (Language)',
        'settings.language_tip': '选择应用界面显示语言，随时自由切换。',
        'settings.currency_and_symbol': '默认结算货币与符号',
        'settings.save_settings': '保存设置',
        'settings.save_success': '设置已成功保存！',

        # 认证
        'auth.login_title': '登录记账本',
        'auth.register_title': '注册新账号',
        'auth.username': '用户名',
        'auth.password': '密码',
        'auth.confirm_password': '确认密码',
        'auth.signin_btn': '立即登录',
        'auth.signup_btn': '立即注册',
        'auth.no_account': '还没有账号？',
        'auth.has_account': '已有账号？点此登录',
    },

    'en': {
        # Navigation
        'nav.brand': 'My Ledger',
        'nav.dashboard': 'Dashboard',
        'nav.records': 'Transactions',
        'nav.split_bill': 'Split Bill',
        'nav.manage': 'Manage',
        'nav.accounts': 'Accounts',
        'nav.liabilities': 'Liabilities',
        'nav.subscriptions': 'Subscriptions',
        'nav.recurring': 'Recurring',
        'nav.settings': 'Settings',
        'nav.tools': 'Tools',
        'nav.insights': 'Category Insights',
        'nav.categories': 'Categories',
        'nav.import': 'Batch Import',
        'nav.privacy': 'Privacy',
        'nav.privacy_title': 'Toggle Privacy Mode (Ctrl+Shift+P)',
        'nav.logout': 'Logout',
        'nav.more': 'More',
        'nav.more_title': 'More Features',
        'nav.overview': 'Overview',
        'nav.details': 'Details',
        'nav.language': 'Language',
        'nav.install_pwa': 'Install App',
        'nav.install_pwa_desc': 'Enjoy full-screen native app experience without browser bars',
        'nav.install_btn': 'Install',
        'nav.close': 'Close',

        # Common
        'common.save': 'Save',
        'common.cancel': 'Cancel',
        'common.delete': 'Delete',
        'common.edit': 'Edit',
        'common.confirm': 'Confirm',
        'common.add': 'Add',
        'common.search': 'Search',
        'common.filter': 'Filter',
        'common.all': 'All',
        'common.total': 'Total',
        'common.subtotal': 'Subtotal',
        'common.amount': 'Amount',
        'common.date': 'Date',
        'common.category': 'Category',
        'common.account': 'Account',
        'common.note': 'Note',
        'common.type': 'Type',
        'common.expense': 'Expense',
        'common.income': 'Income',
        'common.transfer': 'Transfer',
        'common.actions': 'Actions',
        'common.status': 'Status',
        'common.success': 'Success',
        'common.failed': 'Failed',
        'common.loading': 'Loading...',
        'common.no_data': 'No data available',
        'common.currency': 'Currency',
        'common.back': 'Back',
        'common.view': 'View',

        # Dashboard
        'dashboard.title': 'Financial Overview',
        'dashboard.desc': 'Track your net worth, monthly income & expense, and budget health.',
        'dashboard.current_month': 'This Month',
        'dashboard.monthly_expense': 'Monthly Expense',
        'dashboard.monthly_income': 'Monthly Income',
        'dashboard.net_balance': 'Net Balance',
        'dashboard.savings_pool': 'Total Savings',
        'dashboard.quick_add': 'Add Entry',
        'dashboard.quick_add_title': 'Quick Add Transaction',
        'dashboard.recent_transactions': 'Recent Transactions',
        'dashboard.budget': 'Budget',
        'dashboard.budget_used': 'Budget Used',
        'dashboard.view_all': 'View All',
        'dashboard.charts': 'Trends & Analytics',

        # Records
        'records.title': 'Transaction Records',
        'records.desc': 'View, search, and audit all your incoming and outgoing transactions.',
        'records.search_placeholder': 'Search note, amount, or category...',
        'records.filter_type': 'Transaction Type',
        'records.filter_category': 'Filter Category',
        'records.filter_account': 'Filter Account',
        'records.date_range': 'Date Range',
        'records.export_csv': 'Export CSV',
        'records.no_records_hint': 'No matching transaction records found',

        # Split Bill
        'split_bill.title': 'Split Bill with Receipt OCR',
        'split_bill.desc': 'Snap or upload receipts, automatically align items and split with friends in seconds!',
        'split_bill.step1': 'Step 1: Input Receipts',
        'split_bill.step2': 'Step 2: Add Members & Allocate Items',
        'split_bill.step3': 'Step 3: Split Summary & Claim Bill',
        'split_bill.scan_single': '📸 Snap Single Receipt',
        'split_bill.batch_upload': '🖼️ Batch Upload (Multi-meal)',
        'split_bill.manual_paste': 'Or paste raw receipt text / items:',
        'split_bill.grand_total': 'Grand Total',
        'split_bill.service_charge': 'Service Charge / Tip',
        'split_bill.tax': 'Tax / VAT / SST',
        'split_bill.members': 'Split Members',
        'split_bill.add_member': '+ Add Member',
        'split_bill.copy_claim': 'Copy Claim Summary',
        'split_bill.save_to_ledger': 'Save My Share to Ledger',

        # Settings
        'settings.title': 'Personalization & Settings',
        'settings.desc': 'Customize visual theme, default currency, language, and preferences.',
        'settings.appearance': 'Visual Appearance & Layout',
        'settings.theme_mode': 'Theme Mode',
        'settings.theme_system': '🖥️ Follow System',
        'settings.theme_dark': '🌙 Dark Mode',
        'settings.theme_light': '☀️ Light Mode',
        'settings.table_density': 'Table Density',
        'settings.density_comfortable': '🛋️ Comfortable (Recommended)',
        'settings.density_compact': '📑 Compact Mode',
        'settings.language_setting': 'System Language',
        'settings.language_tip': 'Select interface language. Change anytime without reloading.',
        'settings.currency_and_symbol': 'Default Currency & Symbol',
        'settings.save_settings': 'Save Settings',
        'settings.save_success': 'Settings saved successfully!',

        # Auth
        'auth.login_title': 'Login to Ledger',
        'auth.register_title': 'Create New Account',
        'auth.username': 'Username',
        'auth.password': 'Password',
        'auth.confirm_password': 'Confirm Password',
        'auth.signin_btn': 'Sign In',
        'auth.signup_btn': 'Sign Up',
        'auth.no_account': "Don't have an account?",
        'auth.has_account': 'Already have an account? Sign In',
    },

    'ms': {
        # Navigasi
        'nav.brand': 'Buku Lejar Saya',
        'nav.dashboard': 'Papan Pemuka',
        'nav.records': 'Rekod Transaksi',
        'nav.split_bill': 'Bahagi Bil (AA)',
        'nav.manage': 'Pengurusan',
        'nav.accounts': 'Pengurusan Akaun',
        'nav.liabilities': 'Liabiliti & Ansuran',
        'nav.subscriptions': 'Langganan',
        'nav.recurring': 'Tetap Berkala',
        'nav.settings': 'Tetapan',
        'nav.tools': 'Alatan',
        'nav.insights': 'Cerapan Kategori',
        'nav.categories': 'Pengurusan Kategori',
        'nav.import': 'Import Pukal',
        'nav.privacy': 'Privasi',
        'nav.privacy_title': 'Topeng Privasi (Pintas: Ctrl+Shift+P)',
        'nav.logout': 'Log Keluar',
        'nav.more': 'Lain-lain',
        'nav.more_title': 'Ciri-ciri Tambahan',
        'nav.overview': 'Gambaran',
        'nav.details': 'Butiran',
        'nav.language': 'Bahasa',
        'nav.install_pwa': 'Pasang Aplikasi',
        'nav.install_pwa_desc': 'Nikmati pengalaman skrin penuh seperti aplikasi asli',
        'nav.install_btn': 'Pasang',
        'nav.close': 'Tutup',

        # Umum
        'common.save': 'Simpan',
        'common.cancel': 'Batal',
        'common.delete': 'Padam',
        'common.edit': 'Edit',
        'common.confirm': 'Sahkan',
        'common.add': 'Tambah',
        'common.search': 'Cari',
        'common.filter': 'Tapis',
        'common.all': 'Semua',
        'common.total': 'Jumlah Keseluruhan',
        'common.subtotal': 'Jumlah Kecil',
        'common.amount': 'Jumlah',
        'common.date': 'Tarikh',
        'common.category': 'Kategori',
        'common.account': 'Akaun',
        'common.note': 'Nota',
        'common.type': 'Jenis',
        'common.expense': 'Perbelanjaan',
        'common.income': 'Pendapatan',
        'common.transfer': 'Pindahan',
        'common.actions': 'Tindakan',
        'common.status': 'Status',
        'common.success': 'Berjaya',
        'common.failed': 'Gagal',
        'common.loading': 'Memuatkan...',
        'common.no_data': 'Tiada data tersedia',
        'common.currency': 'Mata Wang',
        'common.back': 'Kembali',
        'common.view': 'Lihat',

        # Papan Pemuka
        'dashboard.title': 'Gambaran Kewangan',
        'dashboard.desc': 'Jejak nilai bersih, pendapatan & perbelanjaan bulanan serta belanjawan.',
        'dashboard.current_month': 'Bulan Ini',
        'dashboard.monthly_expense': 'Perbelanjaan Bulanan',
        'dashboard.monthly_income': 'Pendapatan Bulanan',
        'dashboard.net_balance': 'Baki Bersih',
        'dashboard.savings_pool': 'Jumlah Simpanan',
        'dashboard.quick_add': 'Tambah Transaksi',
        'dashboard.quick_add_title': 'Tambah Pantas',
        'dashboard.recent_transactions': 'Transaksi Terkini',
        'dashboard.budget': 'Belanjawan',
        'dashboard.budget_used': 'Belanjawan Digunakan',
        'dashboard.view_all': 'Lihat Semua',
        'dashboard.charts': 'Trend & Analisis',

        # Rekod
        'records.title': 'Rekod Transaksi',
        'records.desc': 'Semak, cari dan selidik semua transaksi masuk dan keluar.',
        'records.search_placeholder': 'Cari nota, jumlah atau kategori...',
        'records.filter_type': 'Jenis Transaksi',
        'records.filter_category': 'Tapis Kategori',
        'records.filter_account': 'Tapis Akaun',
        'records.date_range': 'Julat Tarikh',
        'records.export_csv': 'Eksport CSV',
        'records.no_records_hint': 'Tiada rekod transaksi yang sepadan ditemui',

        # Bahagi Bil
        'split_bill.title': 'Bahagi Bil dengan OCR Resit',
        'split_bill.desc': 'Tangkap gambar resit, susun item secara automatik dan bahagikan bil bersama rakan!',
        'split_bill.step1': 'Langkah 1: Masukkan Resit',
        'split_bill.step2': 'Langkah 2: Tambah Ahli & Agih Item',
        'split_bill.step3': 'Langkah 3: Ringkasan Bahagi & Tuntut',
        'split_bill.scan_single': '📸 Tangkap Resit Tunggal',
        'split_bill.batch_upload': '🖼️ Muat Naik Pukal (Pelbagai Makanan)',
        'split_bill.manual_paste': 'Atau tampal teks resit secara terus:',
        'split_bill.grand_total': 'Jumlah Keseluruhan (Grand Total)',
        'split_bill.service_charge': 'Caj Perkhidmatan / Tip',
        'split_bill.tax': 'Cukai (SST / Tax)',
        'split_bill.members': 'Ahli Pembahagian Bil',
        'split_bill.add_member': '+ Tambah Ahli',
        'split_bill.copy_claim': 'Salin Mesej Tuntutan',
        'split_bill.save_to_ledger': 'Simpan Bahagian Saya ke Lejar',

        # Tetapan
        'settings.title': 'Tetapan & Keutamaan',
        'settings.desc': 'Sesuaikan tema visual, mata wang lalai, bahasa dan kebiasaan sistem.',
        'settings.appearance': 'Penampilan Visual & Susun Atur',
        'settings.theme_mode': 'Mod Tema',
        'settings.theme_system': '🖥️ Ikut Sistem',
        'settings.theme_dark': '🌙 Mod Gelap',
        'settings.theme_light': '☀️ Mod Cerah',
        'settings.table_density': 'Kepadatan Jadual',
        'settings.density_comfortable': '🛋️ Mod Selesa (Disyorkan)',
        'settings.density_compact': '📑 Mod Padat',
        'settings.language_setting': 'Bahasa Sistem (Language)',
        'settings.language_tip': 'Pilih bahasa paparan antaramuka sistem.',
        'settings.currency_and_symbol': 'Mata Wang & Simbol Lalai',
        'settings.save_settings': 'Simpan Tetapan',
        'settings.save_success': 'Tetapan berjaya disimpan!',

        # Pengesahan
        'auth.login_title': 'Log Masuk Lejar',
        'auth.register_title': 'Daftar Akaun Baharu',
        'auth.username': 'Nama Pengguna',
        'auth.password': 'Kata Laluan',
        'auth.confirm_password': 'Sahkan Kata Laluan',
        'auth.signin_btn': 'Log Masuk',
        'auth.signup_btn': 'Daftar Akaun',
        'auth.no_account': 'Belum mempunyai akaun?',
        'auth.has_account': 'Sudah mempunyai akaun? Log Masuk',
    },

    'zh_TW': {
        # 導航
        'nav.brand': '我的帳本',
        'nav.dashboard': '儀表盤',
        'nav.records': '歷史記錄',
        'nav.split_bill': 'AA 分賬',
        'nav.manage': '管理',
        'nav.accounts': '賬戶管理',
        'nav.liabilities': '負債分期',
        'nav.subscriptions': '訂閱大廳',
        'nav.recurring': '固定收支',
        'nav.settings': '偏好設定',
        'nav.tools': '工具',
        'nav.insights': '分類洞察',
        'nav.categories': '分類管理',
        'nav.import': '批量匯入',
        'nav.privacy': '隱私',
        'nav.privacy_title': '隱私遮罩 (快捷鍵: Ctrl+Shift+P)',
        'nav.logout': '登出',
        'nav.more': '更多',
        'nav.more_title': '更多管理功能',
        'nav.overview': '概覽',
        'nav.details': '明細',
        'nav.language': '語言',
        'nav.install_pwa': '安裝為手機應用',
        'nav.install_pwa_desc': '免開瀏覽器，享受全屏原生 App 體驗',
        'nav.install_btn': '安裝',
        'nav.close': '關閉',

        # 通用
        'common.save': '保存',
        'common.cancel': '取消',
        'common.delete': '刪除',
        'common.edit': '編輯',
        'common.confirm': '確認',
        'common.add': '新增',
        'common.search': '搜尋',
        'common.filter': '篩選',
        'common.all': '全部',
        'common.total': '總計',
        'common.subtotal': '單品小計',
        'common.amount': '金額',
        'common.date': '日期',
        'common.category': '分類',
        'common.account': '賬戶',
        'common.note': '備註',
        'common.type': '類型',
        'common.expense': '支出',
        'common.income': '收入',
        'common.transfer': '轉賬',
        'common.actions': '操作',
        'common.status': '狀態',
        'common.success': '成功',
        'common.failed': '失敗',
        'common.loading': '載入中...',
        'common.no_data': '暫無數據',
        'common.currency': '貨幣',
        'common.back': '返回',
        'common.view': '查看',

        # 儀表盤
        'dashboard.title': '財務概覽',
        'dashboard.desc': '清晰掌控你的資產淨值、每月收支與預算健康度。',
        'dashboard.current_month': '本月',
        'dashboard.monthly_expense': '本月支出',
        'dashboard.monthly_income': '本月收入',
        'dashboard.net_balance': '本月結餘',
        'dashboard.savings_pool': '累計總儲蓄',
        'dashboard.quick_add': '記一筆',
        'dashboard.quick_add_title': '快速記賬',
        'dashboard.recent_transactions': '最近交易',
        'dashboard.budget': '預算',
        'dashboard.budget_used': '預算使用',
        'dashboard.view_all': '查看全部',
        'dashboard.charts': '收支趨勢與分析',

        # 交易明細
        'records.title': '交易明細記錄',
        'records.desc': '查看、篩選與追溯每一筆開銷與進賬。',
        'records.search_placeholder': '搜尋備註、金額或分類...',
        'records.filter_type': '交易類型',
        'records.filter_category': '分類篩選',
        'records.filter_account': '賬戶篩選',
        'records.date_range': '日期範圍',
        'records.export_csv': '匯出 CSV',
        'records.no_records_hint': '暫無符合條件的交易記錄',

        # 小票分賬
        'split_bill.title': '小票拍照與智能 AA 分賬',
        'split_bill.desc': '拍攝或上傳小票，智能糾偏與聚合單品，秒算每個人應付多少！',
        'split_bill.step1': '第 1 步：錄入小票資訊',
        'split_bill.step2': '第 2 步：添加參與人 & 分配消費',
        'split_bill.step3': '第 3 步：分攤結果與一鍵催款',
        'split_bill.scan_single': '📸 拍攝單張小票',
        'split_bill.batch_upload': '🖼️ 批量選取多張 (多餐)',
        'split_bill.manual_paste': '或直接粘貼小票文本 / 明細：',
        'split_bill.grand_total': '小票實付總金額 (Grand Total)',
        'split_bill.service_charge': '服務費 / Tip',
        'split_bill.tax': '稅費 (Tax/VAT)',
        'split_bill.members': '參與分賬人員 (Members)',
        'split_bill.add_member': '+ 添加人員',
        'split_bill.copy_claim': '一鍵複製清單發群',
        'split_bill.save_to_ledger': '一鍵記入主賬本',

        # 設定
        'settings.title': '個性化與偏好設定',
        'settings.desc': '定制視覺主題、默認貨幣、語言與系統習慣。',
        'settings.appearance': '視覺外觀與排版',
        'settings.theme_mode': '主題模式',
        'settings.theme_system': '🖥️ 跟隨系統',
        'settings.theme_dark': '🌙 暗黑沉浸',
        'settings.theme_light': '☀️ 明亮淺色',
        'settings.table_density': '明細表格密度',
        'settings.density_comfortable': '🛋️ 舒適模式 (推薦)',
        'settings.density_compact': '📑 緊湊高效模式',
        'settings.language_setting': '系統語言 (Language)',
        'settings.language_tip': '選擇應用介面顯示語言，隨時自由切換。',
        'settings.currency_and_symbol': '默認結算貨幣與符號',
        'settings.save_settings': '保存設定',
        'settings.save_success': '設定已成功保存！',

        # 認證
        'auth.login_title': '登入記賬本',
        'auth.register_title': '註冊新賬號',
        'auth.username': '用戶名',
        'auth.password': '密碼',
        'auth.confirm_password': '確認密碼',
        'auth.signin_btn': '立即登入',
        'auth.signup_btn': '立即註冊',
        'auth.no_account': '還沒有賬號？',
        'auth.has_account': '已有賬號？點此登入',
    }
}


def get_supported_languages():
    """返回支持的语言列表字典清单"""
    return list(SUPPORTED_LANGUAGES.values())


def normalize_locale(lang_code):
    """规范化语言代码"""
    if not lang_code:
        return DEFAULT_LOCALE
    lang_code = str(lang_code).strip()
    if lang_code in SUPPORTED_LANGUAGES:
        return lang_code
    lower = lang_code.lower()
    if lower.startswith('zh-tw') or lower.startswith('zh-hk') or lower == 'zh_tw':
        return 'zh_TW'
    if lower.startswith('zh'):
        return 'zh'
    if lower.startswith('en'):
        return 'en'
    if lower.startswith('ms') or lower.startswith('my'):
        return 'ms'
    return DEFAULT_LOCALE


def get_current_locale():
    """
    智能解析当前请求的语言区域代码 (Locale):
    解析链条:
      1. URL Query 参数 `?lang=xxx`
      2. Session 会话中的 `lang`
      3. 登录用户偏好 `user_settings.language` (从 g 中读取)
      4. Cookie `lang`
      5. HTTP Accept-Language 标头
      6. 默认降级为 `zh` (简体中文)
    """
    # 0. 优先判断当前请求上下文生命周期内的缓存
    if hasattr(g, 'current_lang') and g.current_lang:
        return g.current_lang

    locale = None

    try:
        # 1. URL 参数强行指定
        if request and request.args and request.args.get('lang'):
            cand = request.args.get('lang')
            locale = normalize_locale(cand)

        # 2. Session 会话
        if not locale and session and session.get('lang'):
            locale = normalize_locale(session.get('lang'))

        # 3. 登录用户的个人设置缓存 (由 context processor 或 before_request 挂载)
        if not locale and hasattr(g, 'user_preferred_lang') and g.user_preferred_lang:
            locale = normalize_locale(g.user_preferred_lang)

        # 4. Cookie
        if not locale and request and request.cookies and request.cookies.get('lang'):
            locale = normalize_locale(request.cookies.get('lang'))

        # 5. 请求标头 Accept-Language
        if not locale and request and request.accept_languages:
            best = request.accept_languages.best_match(['zh', 'zh_TW', 'en', 'ms'])
            if best:
                locale = normalize_locale(best)
    except Exception:
        pass

    if not locale or locale not in SUPPORTED_LANGUAGES:
        locale = DEFAULT_LOCALE

    # 挂载到 g 确保同一次请求内多次调用秒级返回
    try:
        g.current_lang = locale
    except Exception:
        pass

    return locale


def set_current_locale(lang_code):
    """设置当前会话的语言，并同步规范化"""
    locale = normalize_locale(lang_code)
    try:
        session['lang'] = locale
        g.current_lang = locale
    except Exception:
        pass
    return locale


def t(key, default=None, lang=None, **kwargs):
    """
    核心多语言翻译函数:
    - key: 字典键名 (如 'nav.dashboard')
    - default: 找不到时的备选文本
    - lang: 显式指定语言 (默认使用当前请求语言 get_current_locale())
    - kwargs: 插值变量，如 name="Admin" -> {name}
    """
    if not key:
        return ''

    target_lang = normalize_locale(lang) if lang else get_current_locale()

    # 1. 尝试从目标语言字典查找
    val = TRANSLATIONS.get(target_lang, {}).get(key)

    # 2. 降级尝试从默认语言 (zh) 查找
    if val is None and target_lang != DEFAULT_LOCALE:
        val = TRANSLATIONS.get(DEFAULT_LOCALE, {}).get(key)

    # 3. 若仍未找到，使用传入 default 或 key 本身
    if val is None:
        val = default if default is not None else key

    # 4. 字符串占位符格式化替换
    if kwargs and isinstance(val, str):
        try:
            return val.format(**kwargs)
        except Exception:
            return val

    return val


def get_client_translations(lang=None):
    """返回用于前端客户端 JavaScript (window.I18N) 的轻量级词典"""
    target_lang = normalize_locale(lang) if lang else get_current_locale()
    base = dict(TRANSLATIONS.get(DEFAULT_LOCALE, {}))
    if target_lang != DEFAULT_LOCALE:
        base.update(TRANSLATIONS.get(target_lang, {}))
    return base

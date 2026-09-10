"""
全球多国小票基准测试套件 (Global Receipt Benchmark Suite)
测试美、欧、日、新、马、中等多国小票格式、税制、小费与货币解析
"""
import sys
import os
sys.path.insert(0, os.path.abspath(os.path.join(os.path.dirname(__file__), '..')))

# 样本 1: 马来西亚餐饮小票 (SROIE 标准: RM, 6% SST, Rounding 抹零, DuitNow)
RECEIPT_MALAYSIA = """
GOOD MOOD KITCHEN
NO.43, JALAN VERVEA 9, SIMPANG AMPAT, PULAU PINANG
REG NO: 202603068078 (MA0343696-P)
Date: 09/09/2026 17:56  Invoice no: 1559  Cashier: Admin
Table: 12  Pax: 2
----------------------------------------
Qty  Item                             Price (MYR)
1    Lemon Chicken Rice Set 柠檬鸡丁饭  14.90
     ·TA 套餐 (Takeaway) (14.90/ea)
1    Luncheon Meat Fried Rice 午餐肉炒饭 11.90
     ·TA (Takeaway) (11.90/ea)
----------------------------------------
Subtotal                              26.80
Service Tax (6%)                       1.61
Bill Rounding                         -0.01
Total (MYR)                           28.40
DUITNOW QR                           28.40
Change                                 0.00
Thank You! Please come again.
"""

# 样本 2: 美国纽约餐厅小票 (USD $, Burger + Fries, 8.875% Sales Tax, 18% Tip 小费)
RECEIPT_USA = """
SHAKE SHACK #102
MADISON SQUARE PARK, NEW YORK, NY 10010
TEL: (212) 889-6600
Server: Michael M.   Table: 04   09/10/2026 12:30 PM
Order: #4421 - Dine In
----------------------------------------
2x ShackBurger Double                 $ 18.50
1x Bacon Cheese Fries                  $  5.99
2x Classic Hand-Spun Shake             $ 12.00
----------------------------------------
SUBTOTAL                               $ 36.49
Sales Tax 8.875%                       $  3.24
Tip / Gratuity (18%)                   $  6.57
AMOUNT DUE                             $ 46.30
VISA ENDING IN 4921                    $ 46.30
CHANGE DUE                             $  0.00
Thank you for dining with us!
"""

# 样本 3: 欧洲法国巴黎咖啡厅 (EUR €, 逗号小数格式, Total TTC / HT, 10% TVA 增值税)
RECEIPT_FRANCE = """
CAFE DE FLORE
172 BOULEVARD SAINT-GERMAIN, 75006 PARIS
SIRET: 784 214 569 00012
Date: 10/09/2026 14:15  Table: 8  Serveur: Pierre
----------------------------------------
2 Croissant pur beurre                 6,40 €
2 Cafe Creme                           11,00 €
1 Tarte Tatin Maison                   8,50 €
----------------------------------------
Total HT                              23,55 €
TVA 10,0%                              2,35 €
TOTAL TTC                             25,90 €
Carte Bancaire                        25,90 €
Rendu                                  0,00 €
Merci de votre visite et a bientot!
"""

# 样本 4: 日本东京居酒屋 (JPY ¥/円, 整数无小数货币, お通し小菜费, 10% 消費税)
RECEIPT_JAPAN = """
鳥貴族 新宿東口店
東京都新宿区新宿3-24-1
TEL: 03-5369-1234
レシート No. 8923  2026/09/10 19:45  レジ: 01
テーブル: 15  人数: 2名
----------------------------------------
2 プレミアムモルツ生ビール              ¥ 740
4 もも貴族焼（たれ）                  ¥ 1,480
1 キャベツ盛（おかわり無料）            ¥ 370
2 お通し（席料）                       ¥ 600
----------------------------------------
小計                                   ¥ 3,190
消費税 (10%)                           ¥ 319
合計金額                               ¥ 3,509
PayPay決済                             ¥ 3,509
お釣り                                 ¥ 0
毎度ありがとうございます！またのお越しを。
"""

# 样本 5: 新加坡特色餐饮小票 (SGD S$, 海南鸡饭, 10% Service Charge + 9% GST)
RECEIPT_SINGAPORE = """
BOON TONG HEE (BALESTIER)
399/401/403 BALESTIER ROAD, SINGAPORE 329801
GST REG NO: M2-0043921-9
Date: 10/09/2026 13:10  Receipt: #0821  Cashier: Siti
----------------------------------------
1 Signature Boiled Chicken (Half)     S$ 22.00
2 Chicken Rice (Fragrant)              S$  3.00
1 Poached Chinese Spinach              S$ 14.00
2 Iced Lemon Barley                    S$  5.60
----------------------------------------
SUBTOTAL                               S$ 44.60
10% Service Charge                     S$  4.46
9% GST                                 S$  4.42
TOTAL PAYABLE                          S$ 53.48
NETS FlashPay                          S$ 53.48
Change Due                             S$  0.00
Thank You For Dining With Us!
"""

# 样本 6: 中国特色餐饮小票 (CNY ¥, 中文菜品, 纸巾/茶位费, 满减优惠, 微信扫码)
RECEIPT_CHINA = """
蜀大侠火锅（春熙路店）
成都市锦江区春熙路88号
单号: 202609100892  台号: B06  客数: 3
收银员: 003  时间: 2026-09-10 20:15
----------------------------------------
品名                      数量    金额
经典牛油红锅               1    ¥ 68.00
精品水牛毛肚               1    ¥ 48.00
大侠上上签牛肉             2    ¥ 64.00
功夫土豆片                 1    ¥ 16.00
自助调料+茶位              3    ¥ 24.00
----------------------------------------
消费小计                         ¥ 220.00
会员专享优惠                     -¥ 20.00
服务费                            ¥ 0.00
实付金额                         ¥ 200.00
微信支付                         ¥ 200.00
找零                              ¥ 0.00
谢谢惠顾，欢迎再次光临！
"""

if __name__ == '__main__':
    from app import parse_receipt_text_to_items
    samples = [
        ('🇲🇾 马来西亚 (SST 6% + Rounding)', RECEIPT_MALAYSIA, 28.40, 'RM'),
        ('🇺🇸 美国 (Sales Tax + 18% Tip)', RECEIPT_USA, 46.30, '$'),
        ('🇪🇺 法国/欧洲 (€ 逗号小数 + 10% TVA)', RECEIPT_FRANCE, 25.90, '€'),
        ('🇯🇵 日本 (円整数 + お通し + 10% 消費税)', RECEIPT_JAPAN, 3509.0, '¥'),
        ('🇸🇬 新加坡 (10% SC + 9% GST)', RECEIPT_SINGAPORE, 53.48, 'S$'),
        ('🇨🇳 中国餐饮 (优惠抵扣 + 微信扫码)', RECEIPT_CHINA, 200.0, '¥'),
    ]

    print("=== 开始全球多国小票基准测试 ===")
    all_passed = True
    for country, text, expected_total, expected_curr in samples:
        res = parse_receipt_text_to_items(text)
        total = res.get('total', 0.0)
        items_cnt = len(res.get('items', []))
        curr = res.get('currency_symbol', 'RM')
        pass_total = abs(total - expected_total) < 0.05
        status = "✅ PASS" if (pass_total and items_cnt >= 2) else "❌ FAIL"
        if status == "❌ FAIL":
            all_passed = False
        print(f"\n[{status}] {country}")
        print(f"   提取单品数: {items_cnt} 项")
        print(f"   币种: {curr} (期望 {expected_curr})")
        print(f"   Subtotal: {res.get('subtotal')}, Service: {res.get('service_charge')}, Tax: {res.get('tax')}, Total: {total} (期望 {expected_total})")
        for it in res.get('items', [])[:3]:
            print(f"     * {it.get('name')} x{it.get('quantity',1)} -> {it.get('price')}")
        if len(res.get('items', [])) > 3:
            print(f"     ... 及其他 {len(res.get('items', [])) - 3} 项")

    print("\n================================")
    if all_passed:
        print("🎉 全部 6 国小票基准测试 100% 通过！")
    else:
        print("⚠️ 存在未通过的测试用例，需升级解析器规则。")

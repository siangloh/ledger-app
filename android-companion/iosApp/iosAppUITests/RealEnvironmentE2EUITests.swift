import XCTest

final class RealEnvironmentE2EUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUpWithError() throws {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launch()
        XCTAssertEqual(app.state, .runningForeground, "App must launch into foreground")
    }

    override func tearDownWithError() throws {
        app?.terminate()
    }

    @discardableResult
    private func tapElement(tag: String, labels: [String], timeout: TimeInterval = 12) -> XCUIElement {
        let idPredicate = NSPredicate(format: "identifier == %@", tag)
        let exactLabelPredicate = NSPredicate(format: "label IN %@", labels)
        let containsClauses = labels.map { _ in "label CONTAINS[c] %@" }.joined(separator: " OR ")
        let containsPredicate = labels.isEmpty
            ? NSPredicate(value: false)
            : NSPredicate(format: containsClauses, argumentArray: labels)

        let deadline = Date().addingTimeInterval(timeout)
        var found: XCUIElement?
        while Date() < deadline {
            let byId = app.descendants(matching: .any).matching(idPredicate).firstMatch
            if byId.exists {
                found = byId
                break
            }
            let byExactLabel = app.descendants(matching: .any).matching(exactLabelPredicate).firstMatch
            if byExactLabel.exists {
                found = byExactLabel
                break
            }
            let byContains = app.descendants(matching: .any).matching(containsPredicate).firstMatch
            if byContains.exists {
                found = byContains
                break
            }
            RunLoop.current.run(until: Date().addingTimeInterval(0.25))
        }

        guard let element = found else {
            XCTFail("Expected element with tag '\(tag)' or labels \(labels) to exist.\nTree:\n\(app.debugDescription)")
            fatalError("Unreachable")
        }
        if element.isHittable {
            element.tap()
        } else {
            element.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        }
        XCTAssertEqual(app.state, .runningForeground, "App must stay runningForeground after tapping \(tag)")
        return element
    }

    private func assertTextExists(_ substrings: [String], timeout: TimeInterval = 10) {
        let clauses = substrings.map { _ in "(label CONTAINS[c] %@ OR value CONTAINS[c] %@)" }
        let format = clauses.joined(separator: " OR ")
        var args: [Any] = []
        for s in substrings {
            args.append(s)
            args.append(s)
        }
        let predicate = NSPredicate(format: format, argumentArray: args)
        let match = app.descendants(matching: .any).matching(predicate).firstMatch
        if !match.waitForExistence(timeout: timeout) {
            XCTFail("Expected text matching any of \(substrings) to appear.\nTree:\n\(app.debugDescription)")
        }
    }

    private func dismissAnySheetOrAlert() {
        let alertBtn = app.alerts.buttons.firstMatch
        if alertBtn.waitForExistence(timeout: 1.5) {
            alertBtn.tap()
            return
        }
        let dismissLabels = ["Close", "Done", "Cancel", "OK", "取消", "关闭", "确定", "好"]
        let btnPredicate = NSPredicate(format: "label IN %@", dismissLabels)
        let dismissBtn = app.buttons.matching(btnPredicate).firstMatch
        if dismissBtn.waitForExistence(timeout: 1.5) {
            if dismissBtn.isHittable {
                dismissBtn.tap()
            } else {
                dismissBtn.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
            }
            return
        }
        let popoverDismiss = app.otherElements["PopoverDismissRegion"]
        if popoverDismiss.exists {
            popoverDismiss.tap()
        }
    }

    func testRealEnvironmentE2EFlows() {
        // Flow 1: Tab 4 (More) -> Language switching (English -> Melayu -> 中文)
        tapElement(tag: "nav_tab_4", labels: ["更多", "More", "Lagi"])

        tapElement(tag: "more_lang_1", labels: ["English"])
        assertTextExists(["Settings"])

        tapElement(tag: "more_lang_2", labels: ["Melayu"])
        assertTextExists(["Tetapan"])

        tapElement(tag: "more_lang_0", labels: ["中文"])
        assertTextExists(["更多设置"])

        // Flow 2: Backup JSON & Export CSV
        tapElement(tag: "more_btn_backup", labels: ["备份数据", "Backup"])
        Thread.sleep(forTimeInterval: 2.0)
        XCTAssertEqual(app.state, .runningForeground, "App must stay runningForeground after Backup")
        dismissAnySheetOrAlert()

        tapElement(tag: "more_btn_csv", labels: ["导出表格", "Export CSV"])
        Thread.sleep(forTimeInterval: 2.0)
        XCTAssertEqual(app.state, .runningForeground, "App must stay runningForeground after Export CSV")
        dismissAnySheetOrAlert()

        // Flow 3: Tab 2 (Add) -> Quick +50 and +10 -> Save -> Verify 60.00 on Overview
        tapElement(tag: "nav_tab_2", labels: ["记账", "Add", "Catat"])
        tapElement(tag: "add_quick_50", labels: ["+50"])
        tapElement(tag: "add_quick_10", labels: ["+10"])
        tapElement(tag: "add_btn_save", labels: ["保存记录", "保存", "Save"])
        assertTextExists(["60.00"])
        XCTAssertEqual(app.state, .runningForeground)

        // Flow 4: Tab 1 (Records) filters & Tab 3 (Analytics) Accounts + hide toggle
        tapElement(tag: "nav_tab_1", labels: ["账单", "Records", "Rekod"])
        tapElement(tag: "records_filter_1", labels: ["支出", "Expense", "Perbelanjaan"])
        tapElement(tag: "records_filter_0", labels: ["全部", "All", "Semua"])

        tapElement(tag: "nav_tab_3", labels: ["分析", "Analytics", "Analisis"])
        tapElement(tag: "analytics_subtab_1", labels: ["资产账户", "Accounts", "Akaun"])
        tapElement(tag: "accounts_toggle_hide", labels: ["隐藏", "显示", "Hide", "Show"])
        XCTAssertEqual(app.state, .runningForeground)

        // Flow 5: Cold restart persistence check
        app.terminate()
        app.launch()
        XCTAssertEqual(app.state, .runningForeground, "App must relaunch cleanly")
        assertTextExists(["60.00"])
    }
}

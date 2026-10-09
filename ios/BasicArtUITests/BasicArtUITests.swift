import XCTest
import UIKit

/// Where tests write cross-platform exchange files and renders. Override with the
/// BASICART_TEST_OUT environment variable (pass TEST_RUNNER_BASICART_TEST_OUT=<dir> to xcodebuild).
let testOutputRoot: String = ProcessInfo.processInfo.environment["BASICART_TEST_OUT"]
    ?? (NSTemporaryDirectory() as NSString).appendingPathComponent("basicart-tests")

let shotDir = testOutputRoot + "/ios-shots/ui"

extension XCTestCase {
    func snap(_ name: String) {
        let shot = XCUIScreen.main.screenshot()
        let a = XCTAttachment(screenshot: shot)
        a.name = name
        a.lifetime = .keepAlways
        add(a)
        try? FileManager.default.createDirectory(atPath: shotDir, withIntermediateDirectories: true)
        let device = UIDevice.current.userInterfaceIdiom == .pad ? "ipad" : "iphone"
        try? shot.pngRepresentation.write(to: URL(fileURLWithPath: "\(shotDir)/\(device)-\(name).png"))
    }
}

final class BasicArtUITests: XCTestCase {
    var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = false
        app = XCUIApplication()
        app.launchArguments = ["-uiTestReset"]
    }

    func createBlank(_ preset: String = "Square") {
        app.buttons["newTile"].tap()
        XCTAssertTrue(app.buttons["createButton"].waitForExistence(timeout: 5))
        app.buttons["size-\(preset)"].firstMatch.tap()
        app.buttons["createButton"].tap()
        XCTAssertTrue(app.buttons["exportButton"].waitForExistence(timeout: 8))
    }

    func canvasPoint(_ dx: CGFloat, _ dy: CGFloat) -> XCUICoordinate {
        app.otherElements["canvasPage"].firstMatch.coordinate(withNormalizedOffset: CGVector(dx: dx, dy: dy))
    }

    /// Taps the canvas with the Text tool and waits until the on-canvas editor has keyboard focus.
    /// Waits for the canvas frame to settle first (panels animate in when the tool changes), and
    /// retries the tap once if focus didn't arrive.
    func beginTyping(at dx: CGFloat, _ dy: CGFloat) -> XCUIElement {
        let canvas = app.otherElements["canvasPage"].firstMatch
        var last = CGRect.zero
        for _ in 0..<20 {
            let f = canvas.frame
            if f == last && f.width > 0 { break }
            last = f
            usleep(150_000)
        }
        let editor = app.textViews["canvasTextEditor"]
        for _ in 0..<2 {
            canvasPoint(dx, dy).tap()
            // The keyboard bar (B/I/U/S…Done) shows with software and hardware keyboards alike.
            if app.buttons["textDoneButton"].waitForExistence(timeout: 5) { break }
            snap("dbg-typing-not-started")
            // Only retry when editing never began (a retry while editing would end it).
            if editor.frame.width > 0 { break }
        }
        XCTAssertTrue(editor.waitForExistence(timeout: 3))
        return editor
    }

    func testHomeNewAndDelete() {
        app.launch()
        XCTAssertTrue(app.buttons["newTile"].waitForExistence(timeout: 5))
        snap("01-home-empty")
        app.buttons["newTile"].tap()
        XCTAssertTrue(app.buttons["createButton"].waitForExistence(timeout: 5))
        snap("02-new-sheet")
        app.buttons["createButton"].tap()
        XCTAssertTrue(app.buttons["exportButton"].waitForExistence(timeout: 8))
        snap("03-editor-blank")
        app.buttons["editorBack"].tap()
        XCTAssertTrue(app.buttons["newTile"].waitForExistence(timeout: 5))
        let tile = app.buttons["projectTile"].firstMatch
        XCTAssertTrue(tile.waitForExistence(timeout: 5))
        // ⋮ menu → Delete → confirm
        app.buttons["tileMenu"].firstMatch.tap()
        app.buttons["Delete project"].tap()
        snap("04-delete-confirm")
        app.alerts.buttons["Delete"].tap()
        XCTAssertFalse(app.buttons["projectTile"].waitForExistence(timeout: 2))
    }

    func testTextFlow() {
        app.launch()
        createBlank()
        app.buttons["tool-text"].tap()
        let editor = beginTyping(at: 0.5, 0.4)
        editor.typeText("Hello brave world")
        snap("10-text-typing")
        // Standard selection while editing: double-tap selects a word and shows the edit menu;
        // canvas gestures must not steal the touch.
        editor.doubleTap()
        sleep(1)
        snap("11-text-selected")
        XCTAssertTrue(app.menuItems["Copy"].exists || app.buttons["Copy"].exists || app.staticTexts["Copy"].exists, "edit menu shows")
        let bold = app.buttons["Bold"].firstMatch
        bold.tap()
        XCTAssertTrue(bold.isSelected, "selected word is bold")
        // Select all from the B/I/U/S bar: the whole text is not all bold yet → B shows off.
        app.buttons["selectAllButton"].tap()
        sleep(1)
        XCTAssertFalse(bold.isSelected)
        bold.tap()
        XCTAssertTrue(bold.isSelected, "whole text bold after Select all + B")
        bold.tap()
        // Re-bold just the last word for the rest of the flow.
        editor.doubleTap()
        bold.tap()
        app.buttons["textDoneButton"].tap()
        sleep(1)
        snap("12-text-done")
        app.buttons["textTab-style"].tap()
        snap("13-style-tab")
        app.buttons["textTab-color"].tap()
        snap("14-color-tab")
        app.buttons["textTab-outline"].tap()
        app.switches.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        snap("15-outline-tab")
        app.buttons["textTab-shadow"].tap()
        app.switches.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
        snap("16-shadow-tab")
        app.buttons["textTab-effects"].tap()
        snap("17-effects-tab")
        app.buttons["preset-neon"].tap()
        sleep(1)
        snap("18-preset-neon")
        app.buttons["textTab-font"].tap()
        app.buttons["fontGroup-clean-sans"].tap()
        sleep(1)
        snap("19-font-families")
        app.buttons["undoButton"].tap()
        app.buttons["layersButton"].tap()
        sleep(1)
        snap("20-layers")
    }

    func testDrawShapesExport() {
        app.launch()
        createBlank("Landscape")
        app.buttons["tool-draw"].tap()
        let start = canvasPoint(0.2, 0.3)
        start.press(forDuration: 0.05, thenDragTo: canvasPoint(0.8, 0.6))
        app.buttons["brush-marker"].tap()
        canvasPoint(0.2, 0.7).press(forDuration: 0.05, thenDragTo: canvasPoint(0.7, 0.35))
        snap("30-draw")
        app.buttons["tool-shapes"].tap()
        canvasPoint(0.3, 0.3).press(forDuration: 0.1, thenDragTo: canvasPoint(0.55, 0.55))
        sleep(1)
        snap("31-shape")
        // Erase across the selected shape (mask on a non-drawing layer).
        app.buttons["tool-draw"].tap()
        app.buttons["brush-eraser"].tap()
        canvasPoint(0.3, 0.42).press(forDuration: 0.05, thenDragTo: canvasPoint(0.6, 0.45))
        sleep(1)
        snap("31b-erased-shape")
        app.buttons["undoButton"].tap()
        app.buttons["redoButton"].tap()
        app.buttons["tool-canvas"].tap()
        snap("32-canvas-panel")
        app.buttons["exportButton"].tap()
        XCTAssertTrue(app.buttons["saveToFiles"].waitForExistence(timeout: 4))
        sleep(1)
        snap("33-export")
        app.buttons["GIF"].tap()
        app.buttons["shareButton"].tap()
        sleep(3)
        snap("34-share")
    }

    func pickFirstPhoto(count: Int = 1) {
        let imgs = app.images.matching(NSPredicate(format: "label BEGINSWITH 'Photo,'"))
        _ = imgs.firstMatch.waitForExistence(timeout: 12)
        if imgs.count == 0 { snap("picker-debug"); XCTFail("no photos in picker"); return }
        for i in 0..<count { imgs.element(boundBy: i).coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap() }
        if count > 1 || app.buttons["Add"].exists {
            if app.buttons["Add"].exists { app.buttons["Add"].tap() }
        }
    }

    func testPhotoMemeFlow() {
        app.launch()
        app.buttons["newTile"].tap()
        XCTAssertTrue(app.buttons["startFromPhoto"].waitForExistence(timeout: 5))
        app.buttons["startFromPhoto"].tap()
        pickFirstPhoto()
        XCTAssertTrue(app.buttons["exportButton"].waitForExistence(timeout: 10))
        sleep(1)
        snap("40-from-photo")
        // Meme by hand: a text box with the Classic Meme preset (presets never add layers).
        app.buttons["tool-text"].tap()
        let editor = beginTyping(at: 0.5, 0.12)
        editor.typeText("Top text")
        app.buttons["textDoneButton"].tap()
        app.buttons["textTab-effects"].tap()
        app.buttons["preset-classic-meme"].tap()
        sleep(1)
        snap("41-meme-text")
        app.buttons["tool-select"].tap()
        // Long-press the photo (bottom layer) for the context menu.
        canvasPoint(0.5, 0.5).press(forDuration: 0.8)
        sleep(1)
        snap("42-context-menu")
        if app.buttons["Move to top"].exists { app.buttons["Move to top"].tap() }
        app.buttons["undoButton"].tap()
        app.buttons["tool-adjust"].tap()
        sleep(1)
        snap("43-adjust")
        app.buttons["cropButton"].tap()
        sleep(1)
        snap("44-crop")
        app.buttons["1:1"].firstMatch.tap()
        app.buttons["Done"].firstMatch.tap()
        sleep(1)
        snap("45-cropped")
    }

    func testCollageImport() {
        app.launch()
        createBlank("Portrait")
        app.buttons["tool-image"].tap()
        pickFirstPhoto(count: 3)
        sleep(3)
        snap("50-collage")
        if !app.staticTexts["Photo 1"].exists { app.buttons["layersButton"].tap() }
        sleep(1)
        snap("51-collage-layers")
        // Drag the bottom layer (Photo 1) to the top of the list.
        let bottom = app.staticTexts["Photo 1"], top = app.staticTexts["Photo 3"]
        XCTAssertTrue(bottom.waitForExistence(timeout: 3))
        bottom.press(forDuration: 1.0, thenDragTo: top)
        sleep(1)
        snap("52-collage-reordered")
        XCTAssertLessThan(app.staticTexts["Photo 1"].frame.minY, app.staticTexts["Photo 2"].frame.minY, "drag reorder moved Photo 1 up")
    }

    func testSettingsAndClearAll() {
        app.launch()
        createBlank()
        app.buttons["editorBack"].tap()
        XCTAssertTrue(app.buttons["projectTile"].waitForExistence(timeout: 5))
        app.buttons["settingsButton"].tap()
        XCTAssertTrue(app.buttons["clearAllButton"].waitForExistence(timeout: 5))
        snap("60-settings")
        app.buttons["Dark"].tap()
        sleep(1)
        snap("61-settings-dark")
        app.buttons["licensesLink"].tap()
        sleep(1)
        snap("62-licenses")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["clearAllButton"].tap()
        snap("63-clear-confirm")
        app.alerts.buttons["Delete everything"].tap()
        let field = app.textFields["deleteConfirmField"]
        XCTAssertTrue(field.waitForExistence(timeout: 3))
        field.typeText("DELETE")
        snap("64-type-delete")
        app.buttons["deleteEverythingButton"].tap()
        sleep(1)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertFalse(app.buttons["projectTile"].waitForExistence(timeout: 2))
        snap("65-home-dark-empty")
        // Restore the theme for other tests.
        app.buttons["settingsButton"].tap()
        app.buttons["System"].tap()
    }

    func testLandscapeLayouts() {
        app.launch()
        createBlank("Story/Phone")
        app.buttons["tool-text"].tap()
        let editor = beginTyping(at: 0.5, 0.3)
        editor.typeText("Landscape")
        // Rotate while typing: the tool bar stays hidden and the text box keeps tracking.
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(2)
        snap("69-rotated-while-typing")
        XCTAssertFalse(app.buttons["tool-select"].isHittable, "tool bar hidden while typing")
        XCUIDevice.shared.orientation = .portrait
        sleep(1)
        app.buttons["textDoneButton"].tap()
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(2)
        snap("70-landscape-editor")
        if app.buttons["sidePanelHandle"].exists {
            app.buttons["sidePanelHandle"].firstMatch.tap()
            sleep(1)
            snap("70b-landscape-side-toggled")
            app.buttons["sidePanelHandle"].firstMatch.tap()
            sleep(1)
        }
        app.buttons["textTab-effects"].tap()
        sleep(1)
        snap("71-landscape-effects")
        XCUIDevice.shared.orientation = .portrait
        sleep(2)
        snap("72-portrait-editor")
        app.buttons["editorBack"].tap()
        sleep(2)
        snap("73-home")
    }

    /// QA #3: with Shapes active, a drag on a photo draws a new shape instead of moving the photo.
    func testShapesDrawOverPhoto() {
        app.launch()
        app.buttons["newTile"].tap()
        XCTAssertTrue(app.buttons["startFromPhoto"].waitForExistence(timeout: 5))
        app.buttons["startFromPhoto"].tap()
        pickFirstPhoto()
        XCTAssertTrue(app.buttons["exportButton"].waitForExistence(timeout: 10))
        app.buttons["tool-shapes"].tap()
        canvasPoint(0.3, 0.35).press(forDuration: 0.1, thenDragTo: canvasPoint(0.6, 0.6))
        sleep(1)
        snap("80-shape-over-photo")
        app.buttons["layersButton"].tap()
        XCTAssertTrue(app.staticTexts["Rectangle 1"].waitForExistence(timeout: 3), "a shape layer was drawn")
    }

    /// Selection rule: select a word, hide the keyboard with the palette button, recolor and resize just that word.
    func testStyleSelectionFromPanel() {
        app.launch()
        createBlank()
        app.buttons["tool-text"].tap()
        let editor = beginTyping(at: 0.5, 0.45)
        editor.typeText("Big sale")
        editor.doubleTap() // selects "sale"
        sleep(1)
        app.buttons["stylePanelButton"].tap()
        XCTAssertTrue(app.buttons["textTab-color"].waitForExistence(timeout: 3), "style panel shows while editing")
        app.buttons["textTab-color"].tap()
        sleep(1)
        snap("81-selection-color-tab")
        let red = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'red'")).firstMatch
        if red.waitForExistence(timeout: 2) { red.tap() }
        app.buttons["textTab-style"].tap()
        let field = app.textFields["Size value"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 3))
        field.tap()
        field.typeText("160\n")
        sleep(1)
        snap("82-selection-styled")
        app.buttons["textTab-font"].tap()
        sleep(1)
        snap("83-selection-font-tab")
    }

    /// Numeric fields select their value on focus so typing replaces it (QA #4).
    func testNumericFieldReplacesValue() {
        app.launch()
        createBlank()
        app.buttons["tool-text"].tap()
        let editor = beginTyping(at: 0.5, 0.45)
        editor.typeText("Size")
        app.buttons["textDoneButton"].tap()
        app.buttons["textTab-style"].tap()
        let field = app.textFields["Size value"].firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 3))
        field.tap()
        sleep(1)
        field.typeText("140")
        snap("84-size-typing")
        // No Return: tapping elsewhere / switching tabs must keep the typed value.
        if !app.buttons["textTab-effects"].isHittable {
            app.otherElements["canvasPage"].firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.9)).tap()
            sleep(1)
        }
        app.buttons["textTab-effects"].tap()
        sleep(1)
        app.buttons["textTab-style"].tap()
        XCTAssertEqual(app.textFields["Size value"].firstMatch.value as? String, "140")
        // Rotation field: after the keyboard goes away, the panel's tab row is visible again.
        app.buttons["textTab-effects"].tap()
        let rot = app.textFields["Rotation value"].firstMatch
        XCTAssertTrue(rot.waitForExistence(timeout: 3))
        rot.tap()
        rot.typeText("-17\n")
        sleep(2)
        snap("84b-after-rotation-return")
        XCTAssertTrue(app.buttons["textTab-font"].isHittable, "tab row not clipped after the keyboard hides")
    }

    /// Eyedropper picks a canvas colour into the text fill (QA: not automated before).
    func testEyedropper() {
        app.launch()
        app.buttons["newTile"].tap()
        XCTAssertTrue(app.buttons["createButton"].waitForExistence(timeout: 5))
        app.segmentedControls["backgroundPicker"].buttons["Color"].tap()
        app.buttons["createButton"].tap()
        XCTAssertTrue(app.buttons["exportButton"].waitForExistence(timeout: 8))
        app.buttons["tool-text"].tap()
        let editor = beginTyping(at: 0.5, 0.4)
        editor.typeText("Pick")
        app.buttons["textDoneButton"].tap()
        app.buttons["textTab-color"].tap()
        app.buttons["Pick from canvas"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Touch the canvas, lift to pick"].waitForExistence(timeout: 2))
        canvasPoint(0.5, 0.65).tap()
        sleep(1)
        snap("85-eyedropper")
        XCTAssertFalse(app.staticTexts["Touch the canvas, lift to pick"].exists)
    }

    /// Export: the success state is visible without scrolling (QA #5).
    func testExportSaveBanner() {
        addUIInterruptionMonitor(withDescription: "Photos") { alert in
            for label in ["Allow", "OK", "Allow Access"] where alert.buttons[label].exists { alert.buttons[label].tap(); return true }
            return false
        }
        app.launch()
        createBlank()
        app.buttons["exportButton"].tap()
        XCTAssertTrue(app.buttons["saveToPhotos"].waitForExistence(timeout: 4))
        app.buttons["JPG"].tap()
        app.buttons["saveToPhotos"].tap()
        app.tap() // trigger the interruption monitor if the permission alert shows
        XCTAssertTrue(app.otherElements["exportBanner"].waitForExistence(timeout: 8) || app.staticTexts["Saved to Photos"].waitForExistence(timeout: 2))
        snap("86-export-saved")
    }

    /// Visual pass helper: iPad/iPhone dark mode editor with a text layer, side panels and export.
    func testDarkModeTour() {
        app.launchArguments += ["-theme", "dark"]
        app.launch()
        createBlank("Landscape")
        app.buttons["tool-text"].tap()
        let editor = beginTyping(at: 0.5, 0.65)
        editor.typeText("Dark mode")
        app.buttons["textDoneButton"].tap()
        sleep(1)
        snap("90-dark-text")
        app.buttons["textTab-effects"].tap()
        sleep(1)
        snap("91-dark-effects")
        app.buttons["exportButton"].tap()
        XCTAssertTrue(app.buttons["saveToPhotos"].waitForExistence(timeout: 4))
        sleep(1)
        snap("92-dark-export")
    }

    /// §11a: export a project file, import it back, and it reopens exactly where we left off.
    func testProjectFileRoundTrip() {
        app.launch()
        createBlank()
        app.buttons["tool-text"].tap()
        let editor = beginTyping(at: 0.5, 0.45)
        editor.typeText("Hello world")
        editor.doubleTap() // selects "world"
        sleep(1)
        app.buttons["stylePanelButton"].tap()
        XCTAssertTrue(app.buttons["textTab-color"].waitForExistence(timeout: 3))
        app.buttons["textTab-color"].tap()
        app.otherElements["canvasPage"].firstMatch.pinch(withScale: 1.5, velocity: 2)
        sleep(1)
        snap("95-before-export")
        app.buttons["editorBack"].tap()
        XCTAssertTrue(app.buttons["tileMenu"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["tileMenu"].firstMatch.tap()
        app.buttons["Export project file"].tap()
        XCTAssertTrue(app.buttons["Save to Files"].waitForExistence(timeout: 8))
        app.buttons["Save to Files"].tap()
        // Document picker: save into the default location.
        let save = app.buttons.matching(NSPredicate(format: "label IN {'Save', 'Move', 'Done'}")).firstMatch
        XCTAssertTrue(save.waitForExistence(timeout: 10))
        // iOS 27's Files picker rearranges its toolbar a moment after it appears; a tap at the
        // first frame lands on empty space. Wait for the button to stop moving, then tap (and
        // tap again if the picker is still up).
        var last = CGRect.zero
        for _ in 0..<20 {
            let f = save.frame
            if f == last { break }
            last = f
            sleep(1)
        }
        snap("96-save-to-files")
        for _ in 0..<3 {
            save.tap()
            sleep(2)
            if !save.exists { break }
        }
        XCTAssertFalse(save.exists, "Files picker saved and closed")
        if app.buttons["Replace"].exists { app.buttons["Replace"].tap() }
        sleep(2)
        app.buttons["importButton"].tap()
        sleep(2)
        snap("97a-after-import-tap")
        // Wait for the Files picker itself, then pick the exported file inside it.
        let zipFile = app.staticTexts["Untitled.zip"].firstMatch
        XCTAssertTrue(zipFile.waitForExistence(timeout: 12), "exported file listed in the Files picker")
        sleep(1)
        snap("97-import-picker")
        zipFile.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).withOffset(CGVector(dx: 0, dy: -50)).tap()
        XCTAssertTrue(app.buttons["exportButton"].waitForExistence(timeout: 10), "imported project opens")
        sleep(2)
        snap("98-after-import")
        XCTAssertTrue(app.staticTexts["Untitled (2)"].exists || app.buttons["Project name: Untitled (2)"].exists, "imported as a new project")
        XCTAssertTrue(app.buttons["textTab-color"].isSelected, "Color tab restored")
    }

    /// N6: rename → theme Dark → reopen in landscape must give the editor the whole screen.
    func testEditorFillsScreenAfterRenameAndTheme() {
        defer { XCUIDevice.shared.orientation = .portrait }
        app.launch()
        createBlank()
        // Rename with the keyboard up, rotating to landscape while the alert is showing.
        app.buttons["Project name: Untitled"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 4))
        field.typeText(" Renamed")
        XCUIDevice.shared.orientation = .landscapeLeft
        sleep(2)
        // The app leaves the foreground with the keyboard up (another app activating).
        XCUIDevice.shared.press(.home)
        sleep(2)
        app.activate()
        sleep(2)
        snap("98-after-reactivate")
        if app.alerts.buttons["Rename"].exists { app.alerts.buttons["Rename"].tap() }
        sleep(1)
        snap("98b-after-rename")
        app.buttons["editorBack"].tap()
        XCTAssertTrue(app.buttons["settingsButton"].waitForExistence(timeout: 5))
        app.buttons["settingsButton"].tap()
        XCTAssertTrue(app.buttons["Dark"].waitForExistence(timeout: 4))
        app.buttons["Dark"].tap()
        sleep(1)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["projectTile"].firstMatch.waitForExistence(timeout: 5))
        app.buttons["projectTile"].firstMatch.tap()
        XCTAssertTrue(app.buttons["tool-select"].waitForExistence(timeout: 8))
        sleep(2)
        snap("99-reopen-after-rename-theme")
        let window = app.windows.firstMatch.frame
        let bar = app.buttons["tool-select"].frame
        XCTAssertGreaterThan(bar.maxY, window.maxY - 60, "tool bar sits at the bottom (\(bar) in \(window))")
        app.buttons["editorBack"].tap()
        app.buttons["settingsButton"].tap()
        app.buttons["System"].tap()
    }

    /// N7: typing low on the canvas scrolls it above the keyboard; after Done (and a preset)
    /// it settles back exactly where it was, also with the side panel docked on iPad.
    func testCanvasSettlesAfterTyping() {
        app.launch()
        createBlank()
        if UIDevice.current.userInterfaceIdiom == .pad, !app.buttons["layersButton"].isSelected {
            app.buttons["layersButton"].tap()
            sleep(1)
        }
        let page = app.otherElements["canvasPage"].firstMatch
        sleep(1)
        let before = page.frame
        app.buttons["tool-text"].tap()
        let editor = beginTyping(at: 0.5, 0.92)
        editor.typeText("Low text")
        app.buttons["textDoneButton"].tap()
        sleep(1)
        if app.buttons["preset-sticker"].waitForExistence(timeout: 3) { app.buttons["preset-sticker"].tap() }
        sleep(2)
        snap("99b-settled-after-typing")
        let after = page.frame
        if UIDevice.current.userInterfaceIdiom == .pad {
            XCTAssertEqual(after.midY, before.midY, accuracy: 4, "canvas back in place (\(before) → \(after))")
            XCTAssertEqual(after.height, before.height, accuracy: 4)
        } else {
            // Phone: the text options sheet is open now; the page fits above it, top unchanged.
            XCTAssertEqual(after.minY, before.minY, accuracy: 4, "canvas top in place (\(before) → \(after))")
            XCTAssertLessThan(after.maxY, app.buttons["textTab-font"].frame.minY, "page above the options")
        }
    }

    /// Cold start: launch to first frame of the home screen.
    func testLaunchPerformance() {
        let opts = XCTMeasureOptions()
        opts.iterationCount = 5
        measure(metrics: [XCTApplicationLaunchMetric(waitUntilResponsive: true)], options: opts) {
            XCUIApplication().launch()
        }
    }
}

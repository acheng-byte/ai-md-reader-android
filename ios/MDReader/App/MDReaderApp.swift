import SwiftUI

@main
struct MDReaderApp: App {
    @StateObject private var model = ReaderModel()

    var body: some Scene {
        WindowGroup {
            ReaderView()
                .environmentObject(model)
                .environmentObject(model.prefs)
                .onOpenURL { url in model.openIncoming(url) }
        }
    }
}

import SwiftUI

@main
struct MsgCryptApp: App {
    @StateObject private var store = AppStore()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(store)
                .environmentObject(store.messaging)
        }
    }
}

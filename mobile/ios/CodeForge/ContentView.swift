import SwiftUI

struct ContentView: View {
    @AppStorage("serverURL") private var storedURL = "http://127.0.0.1:8765/console/"
    @State private var activeURL = URL(string: "http://127.0.0.1:8765/console/")!
    @State private var showSettings = false

    var body: some View {
        ZStack(alignment: .topTrailing) {
            Color(red: 0.02, green: 0.03, blue: 0.05)
                .ignoresSafeArea()

            WebView(url: activeURL)
                .ignoresSafeArea(edges: .bottom)

            Button {
                showSettings = true
            } label: {
                Image(systemName: "slider.horizontal.3")
                    .font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(Color(red: 0.55, green: 0.90, blue: 1.0))
                    .padding(12)
                    .background(.ultraThinMaterial, in: Circle())
                    .overlay(Circle().stroke(Color.white.opacity(0.15), lineWidth: 1))
            }
            .padding(.top, 10)
            .padding(.trailing, 14)
            .accessibilityLabel("Server settings")
        }
        .onAppear {
            activeURL = URL(string: storedURL)
                ?? URL(string: "http://127.0.0.1:8765/console/")!
        }
        .sheet(isPresented: $showSettings) {
            ServerSettingsView(current: $storedURL) { url in
                activeURL = url
            }
        }
    }
}

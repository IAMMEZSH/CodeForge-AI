import SwiftUI

struct ServerSettingsView: View {
    @Binding var current: String
    var onConnect: (URL) -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var draft = ""

    var body: some View {
        NavigationView {
            Form {
                Section {
                    TextField("http://192.168.1.10:8765/console/", text: $draft)
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                } header: {
                    Text("Server URL")
                } footer: {
                    Text("On-device server (iSH): http://127.0.0.1:8765/console/\n" +
                         "Desktop on the same Wi-Fi: http://<desktop-ip>:8765/console/")
                }

                Section {
                    Button("Connect") {
                        if let url = normalize(draft) {
                            current = url.absoluteString
                            onConnect(url)
                            dismiss()
                        }
                    }
                    .disabled(normalize(draft) == nil)
                }
            }
            .navigationTitle("CodeForge")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
            .onAppear { draft = current }
        }
        .preferredColorScheme(.dark)
    }

    private func normalize(_ raw: String) -> URL? {
        var text = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty {
            text = "http://127.0.0.1:8765/console/"
        }
        if !text.hasPrefix("http://") && !text.hasPrefix("https://") {
            text = "http://" + text
        }
        if !text.contains("/console") {
            text = text.trimmingCharacters(in: CharacterSet(charactersIn: "/")) + "/console/"
        }
        return URL(string: text)
    }
}

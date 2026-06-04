import SwiftUI

struct SettingsSheet: View {
    @ObservedObject var prefs: Prefs
    var onChange: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("字号") {
                    HStack(spacing: 12) {
                        Image(systemName: "textformat.size.smaller").foregroundStyle(.secondary)
                        Slider(value: $prefs.fontSize, in: Prefs.fontRange, step: 1)
                            .onChange(of: prefs.fontSize) { _, _ in onChange() }
                        Image(systemName: "textformat.size.larger").foregroundStyle(.secondary)
                        Text("\(Int(prefs.fontSize))")
                            .monospacedDigit().frame(width: 34, alignment: .trailing)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("行间距") {
                    HStack(spacing: 12) {
                        Slider(value: $prefs.lineHeight, in: Prefs.lineRange, step: 0.1)
                            .onChange(of: prefs.lineHeight) { _, _ in onChange() }
                        Text(String(format: "%.1f", prefs.lineHeight))
                            .monospacedDigit().frame(width: 34, alignment: .trailing)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("段间距") {
                    HStack(spacing: 12) {
                        Slider(value: $prefs.paraGap, in: Prefs.paraRange, step: 0.1)
                            .onChange(of: prefs.paraGap) { _, _ in onChange() }
                        Text(String(format: "%.1f", prefs.paraGap))
                            .monospacedDigit().frame(width: 34, alignment: .trailing)
                            .foregroundStyle(.secondary)
                    }
                }
                Section("主题") {
                    Picker("主题", selection: $prefs.themeMode) {
                        Text("跟随系统").tag(0)
                        Text("浅色").tag(1)
                        Text("深色").tag(2)
                    }
                    .pickerStyle(.segmented)
                    .onChange(of: prefs.themeMode) { _, _ in onChange() }
                }
                Section {
                    Button("恢复默认排版") {
                        prefs.resetTypography()
                        onChange()
                    }
                }
            }
            .navigationTitle("显示设置")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
        }
    }
}

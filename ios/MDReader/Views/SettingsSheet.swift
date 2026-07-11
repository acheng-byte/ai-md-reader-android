import SwiftUI

struct SettingsSheet: View {
    @ObservedObject var prefs: Prefs
    var onChange: () -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Form {
                Section("排版") {
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
                Section("显示") {
                    Toggle("护眼模式", isOn: $prefs.eyeProtection)
                        .onChange(of: prefs.eyeProtection) { _, _ in onChange() }

                    Picker("字体", selection: $prefs.fontFamily) {
                        Text("默认").tag("default")
                        Text("宋体").tag("serif")
                        Text("等宽").tag("mono")
                        Text("黑体").tag("sans")
                        Text("楷体").tag("kai")
                        Text("仿宋").tag("fangsong")
                        Text("小标宋").tag("xiaobiao")
                        Text("隶书").tag("lishu")
                        Text("微软雅黑").tag("yahei")
                    }
                    .onChange(of: prefs.fontFamily) { _, _ in onChange() }

                    Toggle("显示 Frontmatter", isOn: $prefs.showFrontmatter)
                        .onChange(of: prefs.showFrontmatter) { _, _ in onChange() }

                    Toggle("显示引用块样式", isOn: $prefs.showCitations)
                        .onChange(of: prefs.showCitations) { _, _ in onChange() }

                    Toggle("隐藏文件名标题", isOn: $prefs.hideTitleHeading)
                        .onChange(of: prefs.hideTitleHeading) { _, _ in onChange() }
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

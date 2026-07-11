import SwiftUI
import UniformTypeIdentifiers

struct ReaderView: View {
    @EnvironmentObject var model: ReaderModel
    @EnvironmentObject var prefs: Prefs
    @Environment(\.colorScheme) private var colorScheme
    @State private var showImporter = false

    private var importTypes: [UTType] {
        var types: [UTType] = [.plainText, .text]
        if let md = UTType(filenameExtension: "md") { types.append(md) }
        if let markdown = UTType(filenameExtension: "markdown") { types.append(markdown) }
        return types
    }

    var body: some View {
        NavigationStack {
            ZStack {
                MarkdownWebView(engine: model.engine)
                    .ignoresSafeArea(.container, edges: .bottom)

                // 源码编辑模式覆盖层
                if model.currentMode == "code" {
                    TextEditor(text: $model.sourceText)
                        .font(.system(.body, design: .monospaced))
                        .padding(8)
                        .background(Color(.systemBackground))
                        .ignoresSafeArea(.container, edges: .bottom)
                        .onChange(of: model.sourceText) { _, _ in
                            model.sourceTextChanged()
                        }
                }
            }
            .navigationTitle(model.currentTitle)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button { model.requestOutline() } label: {
                        Image(systemName: "list.bullet")
                    }
                    .accessibilityLabel("目录")

                    Button { model.toggleMode() } label: {
                        Image(systemName: model.currentMode == "preview"
                              ? "chevron.left.forwardslash.chevron.right" : "doc.richtext")
                    }
                    .accessibilityLabel(model.currentMode == "preview" ? "查看源码" : "查看预览")

                    Button { model.toggleFavorite() } label: {
                        Image(systemName: model.isCurrentFavorite ? "star.fill" : "star")
                    }
                    .accessibilityLabel(model.isCurrentFavorite ? "取消收藏" : "收藏")

                    Menu {
                        Button { showImporter = true } label: { Label("打开", systemImage: "folder") }
                        Button { model.showFavorites = true } label: { Label("收藏夹", systemImage: "star") }
                        Button { model.showHistory = true } label: { Label("打开历史", systemImage: "clock") }
                    } label: {
                        Image(systemName: "ellipsis.circle")
                    }
                    .accessibilityLabel("更多")
                }
            }
        }
        .preferredColorScheme(preferredScheme)
        .overlay(alignment: .bottom) { toastView }
        .fileImporter(isPresented: $showImporter,
                      allowedContentTypes: importTypes,
                      allowsMultipleSelection: false) { result in
            if case .success(let urls) = result, let url = urls.first {
                model.openPicked(url)
            }
        }
        .sheet(isPresented: $model.showSettings) {
            SettingsSheet(prefs: prefs) { model.applySettingsToWeb() }
                .presentationDetents([.medium, .large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $model.showOutline) {
            OutlineSheet(items: model.toc) { id in
                model.scrollTo(id)
                model.showOutline = false
            }
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $model.showHistory) {
            HistorySheet().environmentObject(model)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .sheet(isPresented: $model.showFavorites) {
            FavoritesSheet().environmentObject(model)
                .presentationDetents([.large])
                .presentationDragIndicator(.visible)
        }
        .onAppear { model.updateSystemDark(colorScheme == .dark) }
        .onChange(of: colorScheme) { _, newValue in model.updateSystemDark(newValue == .dark) }
        .onDisappear { model.cleanup() }
    }

    private var preferredScheme: ColorScheme? {
        switch prefs.themeMode {
        case 1: return .light
        case 2: return .dark
        default: return nil
        }
    }

    @ViewBuilder private var toastView: some View {
        if let text = model.toastText {
            Text(text)
                .font(.subheadline)
                .foregroundStyle(.primary)
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
                .background(.ultraThinMaterial, in: Capsule())
                .padding(.bottom, 36)
                .shadow(radius: 6, y: 2)
                .transition(.opacity)
        }
    }
}

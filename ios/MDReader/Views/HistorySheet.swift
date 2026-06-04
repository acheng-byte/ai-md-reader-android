import SwiftUI

struct HistorySheet: View {
    @EnvironmentObject var model: ReaderModel
    @Environment(\.dismiss) private var dismiss

    @State private var entries: [HistoryEntry] = []
    @State private var statuses: [String: DocStatus] = [:]
    @State private var favoriteIds: Set<String> = []

    var body: some View {
        NavigationStack {
            Group {
                if entries.isEmpty {
                    ContentUnavailableView("暂无打开历史", systemImage: "clock")
                } else {
                    List {
                        ForEach(entries) { entry in
                            Button { open(entry) } label: { row(entry) }
                                .buttonStyle(.plain)
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("打开历史")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if !entries.isEmpty {
                        Button("清空", role: .destructive) {
                            model.clearHistory()
                            reload()
                        }
                    }
                }
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
        }
        .onAppear(perform: reload)
    }

    @ViewBuilder private func row(_ entry: HistoryEntry) -> some View {
        let st = statuses[entry.identity] ?? .available
        HStack(spacing: 12) {
            Image(systemName: "doc.text").foregroundStyle(.secondary)
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 5) {
                    if favoriteIds.contains(entry.identity) {
                        Image(systemName: "star.fill").font(.caption2).foregroundStyle(.yellow)
                    }
                    Text(entry.name).lineLimit(1).strikethrough(st == .deleted)
                }
                Text(subtitle(entry, st)).font(.caption).foregroundStyle(color(st))
            }
            Spacer(minLength: 0)
        }
        .contentShape(Rectangle())
        .padding(.vertical, 2)
    }

    private func subtitle(_ entry: HistoryEntry, _ st: DocStatus) -> String {
        let rel = RelFmt.string(entry.time)
        switch st {
        case .available: return rel
        case .expired: return "授权过期 · \(rel)"
        case .deleted: return "已删除 · \(rel)"
        }
    }

    private func color(_ st: DocStatus) -> Color {
        switch st {
        case .available: return .secondary
        case .expired: return .orange
        case .deleted: return .red
        }
    }

    private func reload() {
        entries = model.historyEntries()
        favoriteIds = Set(model.favoriteItems().map { $0.identity })
        let snapshot = entries
        DispatchQueue.global(qos: .userInitiated).async {
            var map: [String: DocStatus] = [:]
            for e in snapshot {
                map[e.identity] = DocAccess.status(bookmark: e.bookmark, identity: e.identity)
            }
            DispatchQueue.main.async { statuses = map }
        }
    }

    private func open(_ entry: HistoryEntry) {
        dismiss()
        model.openFromHistory(entry)
    }
}

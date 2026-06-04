import SwiftUI

struct FavoritesSheet: View {
    @EnvironmentObject var model: ReaderModel
    @Environment(\.dismiss) private var dismiss

    @State private var items: [FavoriteItem] = []

    var body: some View {
        NavigationStack {
            Group {
                if items.isEmpty {
                    ContentUnavailableView("收藏夹还是空的", systemImage: "star",
                                           description: Text("在文档页点顶部星标即可收藏"))
                } else {
                    List {
                        ForEach(items) { item in
                            Button { open(item) } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: "doc.text").foregroundStyle(.secondary)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(item.name).lineLimit(1)
                                        Text(RelFmt.string(item.time))
                                            .font(.caption).foregroundStyle(.secondary)
                                    }
                                    Spacer(minLength: 0)
                                }
                                .contentShape(Rectangle())
                                .padding(.vertical, 2)
                            }
                            .buttonStyle(.plain)
                            .swipeActions(edge: .trailing) {
                                Button(role: .destructive) { remove(item) } label: {
                                    Label("取消收藏", systemImage: "star.slash")
                                }
                            }
                        }
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("收藏夹")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
        }
        .onAppear { items = model.favoriteItems() }
    }

    private func open(_ item: FavoriteItem) {
        dismiss()
        model.openFavorite(item)
    }

    private func remove(_ item: FavoriteItem) {
        model.removeFavorite(item.identity)
        items.removeAll { $0.identity == item.identity }
    }
}

import SwiftUI

struct OutlineSheet: View {
    let items: [TocItem]
    var onSelect: (String) -> Void
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            Group {
                if items.isEmpty {
                    ContentUnavailableView("没有目录", systemImage: "list.bullet",
                                           description: Text("本文档没有标题"))
                } else {
                    List(items) { item in
                        Button { onSelect(item.id) } label: {
                            Text(item.text.isEmpty ? "(无标题)" : item.text)
                                .font(item.level <= 1 ? .headline : .body)
                                .fontWeight(item.level <= 2 ? .semibold : .regular)
                                .foregroundStyle(item.level <= 2 ? Color.primary : Color.secondary)
                                .lineLimit(2)
                                .padding(.leading, CGFloat(max(0, item.level - 1)) * 14)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                    }
                    .listStyle(.plain)
                }
            }
            .navigationTitle("目录")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("完成") { dismiss() } }
            }
        }
    }
}

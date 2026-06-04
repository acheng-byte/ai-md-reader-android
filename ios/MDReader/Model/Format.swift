import Foundation

/// 列表用的相对时间格式化（中文）。仅在主线程的视图 body 中使用。
enum RelFmt {
    static let formatter: RelativeDateTimeFormatter = {
        let f = RelativeDateTimeFormatter()
        f.locale = Locale(identifier: "zh_CN")
        f.unitsStyle = .short
        return f
    }()
    static func string(_ t: Double) -> String {
        formatter.localizedString(for: Date(timeIntervalSince1970: t), relativeTo: Date())
    }
}

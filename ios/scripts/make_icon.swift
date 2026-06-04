#!/usr/bin/env swift
// 生成 1024×1024 App 图标 PNG（品牌蓝底 + 白色「M▾」标记，与 Android 自适应图标一致）。
// 用法：swift ios/scripts/make_icon.swift <输出路径.png>
import AppKit

let size = 1024
let outPath = CommandLine.arguments.count > 1
    ? CommandLine.arguments[1]
    : "icon-1024.png"

guard let rep = NSBitmapImageRep(
    bitmapDataPlanes: nil, pixelsWide: size, pixelsHigh: size,
    bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
    colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0
) else { fatalError("无法创建位图") }

NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)

// 背景（满铺，iOS 会自动圆角）
NSColor(srgbRed: 0x09/255.0, green: 0x69/255.0, blue: 0xDA/255.0, alpha: 1).setFill()
NSBezierPath(rect: NSRect(x: 0, y: 0, width: size, height: size)).fill()

NSColor.white.setStroke()
func strokePath(_ points: [NSPoint]) {
    let p = NSBezierPath()
    p.lineWidth = 74
    p.lineCapStyle = .round
    p.lineJoinStyle = .round
    p.move(to: points[0])
    for pt in points.dropFirst() { p.line(to: pt) }
    p.stroke()
}

// 坐标系 y 向上。M
strokePath([
    NSPoint(x: 300, y: 300), NSPoint(x: 300, y: 720),
    NSPoint(x: 470, y: 545), NSPoint(x: 640, y: 720),
    NSPoint(x: 640, y: 300)
])
// 向下箭头：竖线 + 折角
strokePath([NSPoint(x: 762, y: 720), NSPoint(x: 762, y: 405)])
strokePath([NSPoint(x: 690, y: 478), NSPoint(x: 762, y: 400), NSPoint(x: 834, y: 478)])

NSGraphicsContext.restoreGraphicsState()

guard let png = rep.representation(using: .png, properties: [:]) else { fatalError("PNG 编码失败") }
try! png.write(to: URL(fileURLWithPath: outPath))
print("已生成图标: \(outPath) (\(size)x\(size))")

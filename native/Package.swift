// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "MetalCraftNative",
    platforms: [.macOS(.v14)],
    products: [
        .library(name: "MetalCraftNative", type: .dynamic, targets: ["MetalCraftNative"]),
    ],
    targets: [
        .target(
            name: "MetalCraftNative",
            swiftSettings: [.swiftLanguageMode(.v5)]
        ),
    ]
)

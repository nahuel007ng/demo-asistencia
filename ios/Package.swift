// swift-tools-version: 6.0

import PackageDescription

let package = Package(
    name: "MunicipalBleLab",
    platforms: [
        .iOS(.v15),
    ],
    products: [
        .library(
            name: "MunicipalBleLab",
            targets: ["MunicipalBleLab"]
        ),
    ],
    targets: [
        .target(
            name: "MunicipalBleLab",
            path: "MunicipalBleLab",
            exclude: ["Info.plist", "PrivacyInfo.xcprivacy"],
            swiftSettings: [
                .swiftLanguageMode(.v5),
            ]
        ),
    ]
)

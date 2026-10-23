import Foundation

final class EventStore {
    let directory: URL
    var onWriteFailure: (String) -> Void = { _ in }
    private let csvHandle: FileHandle
    private let jsonHandle: FileHandle
    private var closed = false

    init(directory: URL) throws {
        self.directory = directory
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let csvURL = directory.appendingPathComponent("events.csv")
        let jsonURL = directory.appendingPathComponent("events.jsonl")
        if !FileManager.default.fileExists(atPath: csvURL.path) {
            FileManager.default.createFile(atPath: csvURL.path, contents: nil)
        }
        if !FileManager.default.fileExists(atPath: jsonURL.path) {
            FileManager.default.createFile(atPath: jsonURL.path, contents: nil)
        }
        csvHandle = try FileHandle(forWritingTo: csvURL)
        jsonHandle = try FileHandle(forWritingTo: jsonURL)
        if try csvHandle.seekToEnd() == 0 {
            try write(SessionExport.csvHeader + "\n", to: csvHandle)
        }
    }

    func append(_ event: SessionEvent) {
        if closed { return }
        do {
            try write(event.csvRow() + "\n", to: csvHandle)
            try write(event.jsonLine() + "\n", to: jsonHandle)
        } catch {
            onWriteFailure(error.localizedDescription)
        }
    }

    func writeSummary(_ summary: LabSummary) {
        if closed { return }
        let url = directory.appendingPathComponent("summary.json")
        do {
            try summary.json().data(using: .utf8)?.write(to: url, options: .atomic)
        } catch {
            onWriteFailure(error.localizedDescription)
        }
    }

    func close() {
        if closed { return }
        closed = true
        try? csvHandle.close()
        try? jsonHandle.close()
    }

    private func write(_ text: String, to handle: FileHandle) throws {
        try handle.seekToEnd()
        if let data = text.data(using: .utf8) {
            try handle.write(contentsOf: data)
        }
    }
}

func writeExportJson(directory: URL, summary: LabSummary) -> URL? {
    let outputURL = directory.appendingPathComponent("session.json")
    let jsonlURL = directory.appendingPathComponent("events.jsonl")
    var text = ""
    if let handle = try? FileHandle(forReadingFrom: jsonlURL) {
        defer { try? handle.close() }
        let lines = sequenceLines(handle)
        SessionExport.writeJson(summary: summary, eventLines: lines, output: &text)
    } else {
        SessionExport.writeJson(summary: summary, eventLines: EmptyCollection<String>(), output: &text)
    }
    do {
        try text.data(using: .utf8)?.write(to: outputURL, options: .atomic)
        return outputURL
    } catch {
        return nil
    }
}

private func sequenceLines(_ handle: FileHandle) -> [String] {
    let data = (try? handle.readToEnd()) ?? handle.availableData
    guard let text = String(data: data, encoding: .utf8) else { return [] }
    return text.split(whereSeparator: \.isNewline).map(String.init)
}

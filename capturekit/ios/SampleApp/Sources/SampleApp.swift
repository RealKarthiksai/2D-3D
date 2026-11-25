// CaptureKit SampleApp (iOS). Generate the Xcode project with XcodeGen: `cd ios/SampleApp && xcodegen`.
import SwiftUI
#if os(iOS)
import ARKit
import CaptureKit

@main
struct SampleApp: App {
    var body: some Scene { WindowGroup { RootView() } }
}

struct RootView: View {
    var body: some View {
        NavigationStack {
            List {
                NavigationLink("Raw recorder") { RecorderView(clockCheck: false) }
                NavigationLink("Clock check") { RecorderView(clockCheck: true) }
                NavigationLink("Sensor check") { SensorCheckView() }
            }.navigationTitle("CaptureKit")
        }
    }
}

/// Debug screen for the pre-scan sensor check (ADR 0035): shows the core's message and the manifest summary.
struct SensorCheckView: View {
    @State private var capture = CaptureSession(dir: FileManager.default.temporaryDirectory.appendingPathComponent("sensor_check"))
    @State private var runner: SensorCheckRunner?
    @State private var message = "Press Check, then hold the phone still."
    @State private var summary = ""

    var body: some View {
        VStack(spacing: 16) {
            Text("Sensor check").font(.headline)
            Text(message).multilineTextAlignment(.center)
            Text(summary).font(.caption.monospaced())
            Button("Check") { check() }.buttonStyle(.borderedProminent)
        }
        .padding()
        .onDisappear { runner?.stop() }
    }

    private func check() {
        let r = runner ?? capture.makeSensorCheck()
        runner = r
        summary = ""
        r.run(onUpdate: { message = $0 }, onFinish: { msg, cfg in
            message = msg
            summary = cfg.sorted { $0.key < $1.key }.map { "\($0.key)=\($0.value)" }.joined(separator: "\n")
        })
    }
}

/// Phase 0 raw recorder / clock-check screen. Sessions land in Documents/sessions (Files app visible).
struct RecorderView: View {
    let clockCheck: Bool
    @State private var session = ARSession()
    @State private var recorder: RawRecorder?
    @State private var status = "idle"

    var body: some View {
        VStack(spacing: 16) {
            Text(clockCheck ? "Clock check" : "Raw recorder").font(.headline)
            Text(status).font(.caption.monospaced())
            Button(recorder == nil ? "Start" : "Stop") { recorder == nil ? start() : stop() }
                .buttonStyle(.borderedProminent)
        }
        .onDisappear { stop() }
    }

    private func start() {
        let fmt = DateFormatter(); fmt.dateFormat = "yyyyMMdd_HHmmss"
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let dir = docs.appendingPathComponent("sessions/\(clockCheck ? "clock" : "raw")_\(fmt.string(from: Date()))")
        let r = RawRecorder(dir: dir)
        r.clockCheck = clockCheck
        session.delegate = r
        let cfg = ARWorldTrackingConfiguration()
        cfg.worldAlignment = .gravity
        cfg.planeDetection = [.horizontal, .vertical]
        session.run(cfg, options: [.resetTracking, .removeExistingAnchors])
        r.start()
        recorder = r
        status = "recording \(dir.lastPathComponent)"
    }

    private func stop() {
        guard let r = recorder else { return }
        session.pause()
        r.stop()
        status = "saved \(r.dir.lastPathComponent), dropped=\(r.droppedCount)"
        recorder = nil
    }
}
#endif

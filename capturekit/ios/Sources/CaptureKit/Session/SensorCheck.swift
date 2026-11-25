// Pre-scan sensor check (ADR 0035). UNVERIFIED: never compiled (no Mac). All judgement is in the core
// (ck_sensor_check); this only collects CoreMotion samples, shows `message` and follows `action`.
#if os(iOS) && canImport(ARKit) && canImport(CCaptureKit)
import CCaptureKit
import CoreMotion
import Foundation

/// Runs on the app's single CMMotionManager (CaptureSession.motion). Stop it before CaptureSession.start(config:),
/// then pass `config` there: the summary lands in the manifest as capture_settings.sensor_check.
/// The fresh AR start is CaptureSession.start itself (.resetTracking), so `ar_reset` is always 1.
public final class SensorCheckRunner {
    private let motion: CMMotionManager
    private let queue = OperationQueue()
    private let lock = NSLock()
    private var gyro: [ck_imu] = []
    private var accel: [ck_imu] = []
    private var timer: Timer?
    private var startedAt: TimeInterval = 0
    private var restarts = 0
    public private(set) var attempts = 0
    /// 5 s at 200 Hz: more than the core's 2 s window.
    private static let capacity = 1000

    init(motion: CMMotionManager) {
        self.motion = motion
        queue.name = "dev.capturekit.sensorcheck"
        queue.maxConcurrentOperationCount = 1
    }

    /// Main thread. `onUpdate(message)` each step; `onFinish(message, config)` once (pass or not: never blocks the scan).
    public func run(onUpdate: @escaping (String) -> Void, onFinish: @escaping (String, [String: Any]) -> Void) {
        stop()
        attempts += 1
        restarts = 0
        clear()
        guard motion.isGyroAvailable, motion.isAccelerometerAvailable else {
            startedAt = 0  // no sensors: the core times out at once with NO_GYRO / NO_ACCEL
            step(onUpdate: onUpdate, onFinish: onFinish)
            return
        }
        motion.gyroUpdateInterval = 1.0 / 200
        motion.accelerometerUpdateInterval = 1.0 / 200
        motion.startGyroUpdates(to: queue) { [weak self] d, _ in
            guard let self, let d else { return }
            self.append(ck_imu(t_ns: Self.tNs(d.timestamp), v: (d.rotationRate.x, d.rotationRate.y, d.rotationRate.z)), gyro: true)
        }
        motion.startAccelerometerUpdates(to: queue) { [weak self] d, _ in
            guard let self, let d else { return }
            // CoreMotion reports g; the core expects m/s^2 (same scaling as CaptureSession).
            self.append(ck_imu(t_ns: Self.tNs(d.timestamp),
                               v: (d.acceleration.x * 9.80665, d.acceleration.y * 9.80665, d.acceleration.z * 9.80665)), gyro: false)
        }
        startedAt = ProcessInfo.processInfo.systemUptime
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            self?.step(onUpdate: onUpdate, onFinish: onFinish)
        }
    }

    public func stop() {
        timer?.invalidate()
        timer = nil
        motion.stopGyroUpdates()
        motion.stopAccelerometerUpdates()
    }

    /// Session config when the user starts the scan during the check.
    public static func skipped(attempts: Int) -> [String: Any] {
        ["sensor_check.result": "skipped", "sensor_check.attempts": attempts, "sensor_check.ar_reset": 1]
    }

    private static func tNs(_ s: TimeInterval) -> Int64 { Int64((s * 1e9).rounded()) }

    private func append(_ m: ck_imu, gyro isGyro: Bool) {
        lock.lock(); defer { lock.unlock() }
        if isGyro { gyro.append(m); if gyro.count > Self.capacity + 200 { gyro.removeFirst(gyro.count - Self.capacity) } }
        else { accel.append(m); if accel.count > Self.capacity + 200 { accel.removeFirst(accel.count - Self.capacity) } }
    }

    private func clear() { lock.lock(); gyro.removeAll(); accel.removeAll(); lock.unlock() }

    private func step(onUpdate: (String) -> Void, onFinish: (String, [String: Any]) -> Void) {
        lock.lock(); let g = gyro, a = accel; lock.unlock()
        let elapsed = startedAt == 0 ? 1e9 : ProcessInfo.processInfo.systemUptime - startedAt
        // CoreMotion has no per-sample accuracy flag for raw gyro/accel: -1 = unknown.
        let input = "{\"gyro_accuracy\":-1,\"accel_accuracy\":-1,\"elapsed_s\":\(elapsed),\"restarts\":\(restarts)}"
        var out = [CChar](repeating: 0, count: 2048)
        let st = g.withUnsafeBufferPointer { gp in
            a.withUnsafeBufferPointer { ap in
                ck_sensor_check(gp.baseAddress, gp.count, ap.baseAddress, ap.count, input, &out, out.count)
            }
        }
        guard st == CK_OK,
              let obj = try? JSONSerialization.jsonObject(with: Data(String(cString: out).utf8)) as? [String: Any],
              let action = obj["action"] as? String, let message = obj["message"] as? String else { return }
        onUpdate(message)
        switch action {
        case "restart":
            restarts += 1
            clear()
            startedAt = ProcessInfo.processInfo.systemUptime
        case "finish":
            stop()
            var cfg = obj.filter { $0.key.hasPrefix("sensor_check.") }
            cfg["sensor_check.attempts"] = attempts
            cfg["sensor_check.ar_reset"] = 1
            onFinish(message, cfg)
        default:
            break
        }
    }
}
#endif

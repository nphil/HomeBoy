import SwiftUI
import AVFoundation

// MARK: - Public scanner sheet

struct BarcodeScannerSheet: View {
    enum Mode { case barcode, qr }
    let mode: Mode
    /// Called on the main thread once a code is scanned. Parent dismisses the sheet.
    let onScanned: (String) -> Void

    var body: some View {
        _ScannerRepresentable(mode: mode, onScanned: onScanned)
            .ignoresSafeArea()
            .background(Color.black.ignoresSafeArea())
    }
}

// MARK: - Product match review sheet

struct ProductMatchSheet: View {
    @EnvironmentObject var theme: ThemeManager
    @Environment(\.dismiss) var dismiss

    let products: [HBBarcodeProduct]
    let onAccept: (HBBarcodeProduct) -> Void
    let onScanAgain: () -> Void

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 14) {
                    ForEach(products.indices, id: \.self) { productCard(products[$0]) }
                }
                .padding(16)
            }
            .scrollIndicators(.hidden)
            .background(theme.current.backgroundColor.ignoresSafeArea())
            .navigationTitle(products.count == 1 ? "Product Found" : "\(products.count) Matches")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("Scan Again") {
                        dismiss()
                        DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) { onScanAgain() }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }

    @ViewBuilder
    private func productCard(_ product: HBBarcodeProduct) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if let name = product.item?.name, !name.isEmpty {
                Text(name).font(.title3.weight(.semibold))
            }
            VStack(spacing: 6) {
                if let mfr = product.manufacturer, !mfr.isEmpty { infoRow("Manufacturer", mfr) }
                if let model = product.modelNumber, !model.isEmpty { infoRow("Model", model) }
                if let desc = product.item?.description, !desc.isEmpty { infoRow("Description", desc) }
            }
            if let notes = product.notes, !notes.isEmpty {
                Text(notes)
                    .font(.callout).foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            if let engine = product.searchEngineName, !engine.isEmpty {
                Text("via \(engine)").font(.caption2).foregroundStyle(.tertiary)
            }
            Button {
                onAccept(product)
                dismiss()
            } label: {
                Text("Use This")
                    .font(.body.weight(.semibold))
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
            }
            .buttonStyle(.glassProminent)
            .padding(.top, 4)
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 16).fill(.ultraThinMaterial)
            RoundedRectangle(cornerRadius: 16).fill(theme.current.accentColor.opacity(0.05))
        }
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(theme.current.accentColor.opacity(0.2), lineWidth: 1))
    }

    @ViewBuilder
    private func infoRow(_ label: String, _ value: String) -> some View {
        HStack(alignment: .firstTextBaseline) {
            Text(label).font(.caption.weight(.medium)).foregroundStyle(.secondary)
            Spacer()
            Text(value).font(.callout).multilineTextAlignment(.trailing)
        }
    }
}

// MARK: - UIViewControllerRepresentable bridge

private struct _ScannerRepresentable: UIViewControllerRepresentable {
    let mode: BarcodeScannerSheet.Mode
    let onScanned: (String) -> Void

    func makeUIViewController(context: Context) -> _ScannerVC { _ScannerVC(mode: mode, onScanned: onScanned) }
    func updateUIViewController(_ vc: _ScannerVC, context: Context) {}
}

// MARK: - UIKit scanner

final class _ScannerVC: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let mode: BarcodeScannerSheet.Mode
    private let onScanned: (String) -> Void
    private let session = AVCaptureSession()
    private var previewLayer: AVCaptureVideoPreviewLayer?
    private var hasScanned = false
    private var overlayDone = false

    init(mode: BarcodeScannerSheet.Mode, onScanned: @escaping (String) -> Void) {
        self.mode = mode; self.onScanned = onScanned
        super.init(nibName: nil, bundle: nil)
    }
    required init?(coder: NSCoder) { fatalError() }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        checkPermission()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        hasScanned = false
        DispatchQueue.global(qos: .userInitiated).async {
            if !self.session.isRunning { self.session.startRunning() }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        DispatchQueue.global(qos: .userInitiated).async {
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
        if !overlayDone && !view.bounds.isEmpty && !session.inputs.isEmpty {
            overlayDone = true
            buildOverlay()
        }
    }

    private func checkPermission() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized: setupSession()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] ok in
                DispatchQueue.main.async { ok ? self?.setupSession() : self?.showDenied() }
            }
        default: DispatchQueue.main.async { self.showDenied() }
        }
    }

    private func setupSession() {
        guard let device = AVCaptureDevice.default(for: .video),
              let input = try? AVCaptureDeviceInput(device: device),
              session.canAddInput(input) else { return }

        session.beginConfiguration()
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        if session.canAddOutput(output) {
            session.addOutput(output)
            output.setMetadataObjectsDelegate(self, queue: .main)
            let avail = output.availableMetadataObjectTypes
            let want: [AVMetadataObject.ObjectType] = mode == .qr
                ? [.qr]
                : [.ean8, .ean13, .upce, .code128, .code39, .code93, .itf14, .dataMatrix]
            output.metadataObjectTypes = want.filter { avail.contains($0) }
        }
        session.commitConfiguration()

        let prev = AVCaptureVideoPreviewLayer(session: session)
        prev.videoGravity = .resizeAspectFill
        prev.frame = view.bounds
        view.layer.insertSublayer(prev, at: 0)
        previewLayer = prev

        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    private func buildOverlay() {
        let b = view.bounds
        let scanW: CGFloat = mode == .qr ? 256 : 288
        let scanH: CGFloat = mode == .qr ? 256 : 140
        let scanRect = CGRect(
            x: (b.width - scanW) / 2,
            y: (b.height - scanH) / 2 - 40,
            width: scanW, height: scanH
        )

        let dim = CAShapeLayer()
        let path = UIBezierPath(rect: b)
        path.append(UIBezierPath(roundedRect: scanRect, cornerRadius: 10).reversing())
        dim.path = path.cgPath
        dim.fillColor = UIColor.black.withAlphaComponent(0.5).cgColor
        view.layer.addSublayer(dim)

        let bLen: CGFloat = 22; let bW: CGFloat = 3
        for (cx, cy, hd, vd): (CGFloat, CGFloat, CGFloat, CGFloat) in [
            (scanRect.minX, scanRect.minY,  1,  1),
            (scanRect.maxX, scanRect.minY, -1,  1),
            (scanRect.minX, scanRect.maxY,  1, -1),
            (scanRect.maxX, scanRect.maxY, -1, -1),
        ] {
            for (dx, dy): (CGFloat, CGFloat) in [(bLen * hd, 0), (0, bLen * vd)] {
                let l = CAShapeLayer(); let p = UIBezierPath()
                p.move(to: CGPoint(x: cx, y: cy))
                p.addLine(to: CGPoint(x: cx + dx, y: cy + dy))
                l.path = p.cgPath; l.strokeColor = UIColor.white.cgColor
                l.lineWidth = bW; l.lineCap = .round
                view.layer.addSublayer(l)
            }
        }

        let hint = UILabel()
        hint.text = mode == .qr ? "Scan Homebox QR code" : "Point at a product barcode"
        hint.textColor = .white
        hint.font = .systemFont(ofSize: 14, weight: .medium)
        hint.textAlignment = .center
        hint.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(hint)
        NSLayoutConstraint.activate([
            hint.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            hint.topAnchor.constraint(equalTo: view.topAnchor, constant: scanRect.maxY + 20),
        ])
    }

    private func showDenied() {
        let lbl = UILabel()
        lbl.text = "Camera access required.\nAllow camera access in Settings to scan codes."
        lbl.textColor = .white; lbl.numberOfLines = 0; lbl.textAlignment = .center
        lbl.font = .systemFont(ofSize: 15)
        lbl.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(lbl)

        // Recovery path: jump straight to the app's Settings page.
        var config = UIButton.Configuration.filled()
        config.title = "Open Settings"
        config.baseForegroundColor = .white
        config.baseBackgroundColor = UIColor.white.withAlphaComponent(0.2)
        config.cornerStyle = .medium
        config.contentInsets = NSDirectionalEdgeInsets(top: 10, leading: 18, bottom: 10, trailing: 18)
        let settingsButton = UIButton(configuration: config, primaryAction: UIAction { _ in
            if let url = URL(string: UIApplication.openSettingsURLString) {
                UIApplication.shared.open(url)
            }
        })
        settingsButton.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(settingsButton)

        NSLayoutConstraint.activate([
            lbl.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            lbl.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            lbl.widthAnchor.constraint(equalTo: view.widthAnchor, constant: -64),
            settingsButton.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            settingsButton.topAnchor.constraint(equalTo: lbl.bottomAnchor, constant: 16),
        ])
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !hasScanned,
              let obj = objects.first as? AVMetadataMachineReadableCodeObject,
              let code = obj.stringValue else { return }
        hasScanned = true
        DispatchQueue.global(qos: .userInitiated).async { self.session.stopRunning() }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        onScanned(code)
    }
}

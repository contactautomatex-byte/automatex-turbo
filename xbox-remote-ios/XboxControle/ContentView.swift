import SwiftUI

struct ContentView: View {
    @StateObject private var xbox = SmartGlassClient()
    @AppStorage("xbox_ip") private var xboxIP = "192.168.1.14"

    var body: some View {
        GeometryReader { geo in
            ZStack {
                Color(red: 0.03, green: 0.045, blue: 0.065).ignoresSafeArea()

                VStack(spacing: 10) {
                    topBar
                    Spacer(minLength: 0)
                    HStack(alignment: .bottom, spacing: max(24, geo.size.width * 0.08)) {
                        dpad(size: controlClusterSize(in: geo.size))
                        Spacer(minLength: 20)
                        faceButtons(size: controlClusterSize(in: geo.size))
                    }
                    .padding(.horizontal, max(26, geo.size.width * 0.035))
                    .padding(.bottom, max(16, geo.size.height * 0.05))
                }
                .padding(.horizontal, 14)
                .padding(.top, 10)
            }
        }
        .preferredColorScheme(.dark)
        .onDisappear { xbox.disconnect() }
    }

    private var topBar: some View {
        HStack(spacing: 8) {
            Text("XBOX")
                .font(.system(size: 13, weight: .bold, design: .rounded))
                .foregroundStyle(.white)
                .padding(.horizontal, 14)
                .frame(height: 38)
                .background(Color(red: 0.06, green: 0.49, blue: 0.06), in: RoundedRectangle(cornerRadius: 13))

            Text(xbox.status)
                .font(.system(size: 13, weight: .semibold))
                .lineLimit(1)
                .foregroundStyle(xbox.connected ? Color.green : Color.white)
                .frame(maxWidth: .infinity, alignment: .leading)

            TextField("IP do Xbox", text: $xboxIP)
                .keyboardType(.numbersAndPunctuation)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(.system(size: 12, weight: .medium))
                .padding(.horizontal, 10)
                .frame(width: 138, height: 38)
                .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 10))

            smallButton("VIEW", mask: SmartGlassClient.btnView)
            smallButton("⌂", mask: SmartGlassClient.btnNexus, fontSize: 22)
            smallButton("MENU", mask: SmartGlassClient.btnMenu)

            Button(xbox.connected ? "Reconectar" : "Conectar") {
                xbox.connect(ip: xboxIP)
            }
            .buttonStyle(.plain)
            .font(.system(size: 12, weight: .bold))
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .frame(height: 38)
            .background(Color(red: 0.06, green: 0.49, blue: 0.06), in: RoundedRectangle(cornerRadius: 12))
        }
        .padding(8)
        .background(Color.white.opacity(0.065), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color.white.opacity(0.10), lineWidth: 1))
    }

    private func dpad(size: CGFloat) -> some View {
        let button = max(58, min(86, size / 3.15))
        return ZStack {
            RoundedRectangle(cornerRadius: 30)
                .fill(Color.white.opacity(0.045))
                .overlay(RoundedRectangle(cornerRadius: 30).stroke(Color.white.opacity(0.10)))

            VStack(spacing: 4) {
                remoteButton("▲", SmartGlassClient.btnUp, width: button, height: button, font: 25, color: .white.opacity(0.12), corner: 18)
                HStack(spacing: 4) {
                    remoteButton("◀", SmartGlassClient.btnLeft, width: button, height: button, font: 25, color: .white.opacity(0.12), corner: 18)
                    RoundedRectangle(cornerRadius: 16).fill(Color.white.opacity(0.13)).frame(width: button, height: button)
                    remoteButton("▶", SmartGlassClient.btnRight, width: button, height: button, font: 25, color: .white.opacity(0.12), corner: 18)
                }
                remoteButton("▼", SmartGlassClient.btnDown, width: button, height: button, font: 25, color: .white.opacity(0.12), corner: 18)
            }
            .padding(8)
        }
        .frame(width: size, height: size)
    }

    private func faceButtons(size: CGFloat) -> some View {
        let button = max(58, min(86, size / 3.15))
        return ZStack {
            RoundedRectangle(cornerRadius: 30)
                .fill(Color.white.opacity(0.045))
                .overlay(RoundedRectangle(cornerRadius: 30).stroke(Color.white.opacity(0.10)))

            VStack(spacing: 4) {
                remoteButton("Y", SmartGlassClient.btnY, width: button, height: button, font: 27, color: Color(red: 0.96, green: 0.76, blue: 0.16), text: .black, corner: button / 2)
                HStack(spacing: 4) {
                    remoteButton("X", SmartGlassClient.btnX, width: button, height: button, font: 27, color: Color(red: 0.12, green: 0.48, blue: 0.86), corner: button / 2)
                    Color.clear.frame(width: button, height: button)
                    remoteButton("B", SmartGlassClient.btnB, width: button, height: button, font: 27, color: Color(red: 0.86, green: 0.18, blue: 0.17), corner: button / 2)
                }
                remoteButton("A", SmartGlassClient.btnA, width: button, height: button, font: 27, color: Color(red: 0.06, green: 0.49, blue: 0.06), corner: button / 2)
            }
            .padding(8)
        }
        .frame(width: size, height: size)
    }

    private func smallButton(_ label: String, mask: UInt16, fontSize: CGFloat = 11) -> some View {
        Button {
            xbox.sendButton(mask)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        } label: {
            Text(label)
                .font(.system(size: fontSize, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: label == "⌂" ? 48 : 64, height: 38)
                .background(Color.white.opacity(0.10), in: RoundedRectangle(cornerRadius: 11))
        }
        .buttonStyle(.plain)
        .disabled(!xbox.connected)
        .opacity(xbox.connected ? 1 : 0.42)
    }

    private func remoteButton(_ label: String, _ mask: UInt16, width: CGFloat, height: CGFloat, font: CGFloat, color: Color, text: Color = .white, corner: CGFloat) -> some View {
        Button {
            xbox.sendButton(mask)
            UIImpactFeedbackGenerator(style: .light).impactOccurred()
        } label: {
            Text(label)
                .font(.system(size: font, weight: .bold, design: .rounded))
                .foregroundStyle(text)
                .frame(width: width, height: height)
                .background(color, in: RoundedRectangle(cornerRadius: corner))
        }
        .buttonStyle(PressButtonStyle())
        .disabled(!xbox.connected)
        .opacity(xbox.connected ? 1 : 0.42)
    }

    private func controlClusterSize(in size: CGSize) -> CGFloat {
        let byHeight = max(200, size.height - 98)
        let byWidth = max(200, (size.width - 110) * 0.34)
        return min(285, min(byHeight, byWidth))
    }
}

private struct PressButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.94 : 1)
            .brightness(configuration.isPressed ? 0.12 : 0)
            .animation(.easeOut(duration: 0.08), value: configuration.isPressed)
    }
}

import Foundation
import OctoKit
import Testing

@Suite struct CertificateLineTests {
    /// Red when the row loses None chosen, shows a day it does not have, or drops the day it has.
    @Test func theRowShowsTheNameAndItsDayOnceKnown() {
        let day = Date(timeIntervalSince1970: 1_900_000_000)
        #expect(certificateRowLine(name: nil, expires: day, format: { _ in "Mar 17, 2030" }) == "None chosen")
        #expect(certificateRowLine(name: "client.p12", expires: nil, format: { _ in "Mar 17, 2030" }) == "client.p12")
        #expect(certificateRowLine(name: "client.p12", expires: day, format: { _ in "Mar 17, 2030" }) == "client.p12, expires Mar 17, 2030")
    }

    /// Red when the day handed to the format is not the certificate's own.
    @Test func theFormatGetsTheCertificatesOwnDay() {
        let day = Date(timeIntervalSince1970: 1_900_000_000)
        var seen: Date?
        _ = certificateRowLine(name: "client.p12", expires: day, format: { seen = $0; return "x" })
        #expect(seen == day)
    }
}

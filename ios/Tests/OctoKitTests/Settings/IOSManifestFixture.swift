// Written by scripts/release/test_release_assets.py --write-ios-fixture: an iPhone release's
// manifest as the release scripts publish it, signed with a throwaway test key no app trusts.
enum IOSManifestFixture {
    static let tag = "ios-v1.11.0"
    static let manifest = "eyJhc3NldHMiOlt7ImFyY2giOiJhcm02NCIsImtpbmQiOiJpcGEiLCJuYW1lIjoiT2N0by0xLjExLjAtaVBob25lLmlwYSIsIm9zIjoiaW9zIiwic2hhMjU2IjoiNjlmMTUzMjk3MGVhMzhkOWY4Mzg4Yzc2NTVjYmQzNmQ3N2Q4MTAyZWZjNzFhZGFhMWQ3Nzg0MmVkZDFkM2ZjNCIsInNpemUiOjE1fV0sImZvcm1hdCI6MSwibm90ZXMiOiItIEZpcnN0IGlQaG9uZSByZWxlYXNlLiIsInByb2R1Y3QiOiJpb3MiLCJ0YWciOiJpb3MtdjEuMTEuMCIsInZlcnNpb24iOiIxLjExLjAifQ=="
    static let signature = "HJWym/L4s6oZnqq9vC/0TH4ZhaeDCGzC1QJvBF+06Azh9um+G55+euWvUf592oHKuaMnGZaNgJoDMwmXooCMCA=="
    static let publicKey = "B7uL13u1MWGnL639nRABmwTegi5EBx8FCBnsrI+xwEs="
    static let ipaSha256 = "69f1532970ea38d9f8388c7655cbd36d77d8102efc71adaa1d77842edd1d3fc4"
}

import Foundation
import OctoKit
import Security

/// Server secrets (passwords, keys, header values, client certificates), kept in the keychain and
/// never in preferences.
enum Keychain {
    private static let service = "app.winters.octo.server"

    /// True once the keychain holds the secret.
    @discardableResult
    static func save(_ secret: String, for account: String) -> Bool {
        saveData(Data(secret.utf8), for: account)
    }

    static func read(for account: String) -> String? {
        readData(for: account).flatMap { String(data: $0, encoding: .utf8) }
    }

    /// Writes the item in place, or adds it when there is none, so a write that fails leaves the
    /// old value; true once the keychain holds `data`. Every item Octo makes is readable after the
    /// first unlock, so background playback can sign its requests.
    @discardableResult
    static func saveData(_ data: Data, for account: String) -> Bool {
        let item = query(for: account)
        let update = SecItemUpdate(item as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if update == errSecSuccess { return true }
        guard update == errSecItemNotFound else { return false }
        var added = item
        added[kSecValueData as String] = data
        added[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(added as CFDictionary, nil) == errSecSuccess
    }

    static func readData(for account: String) -> Data? {
        if case .found(let data) = lookup(for: account) { return data }
        return nil
    }

    /// What the keychain holds for `account`: the data, no item, or a read that failed.
    static func lookup(for account: String) -> SecretRead {
        var found = query(for: account)
        found[kSecReturnData as String] = true
        found[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        let status = SecItemCopyMatching(found as CFDictionary, &result)
        return SecretRead(status: status, data: result as? Data)
    }

    static func delete(for account: String) {
        SecItemDelete(query(for: account) as CFDictionary)
    }

    private static func query(for account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
        ]
    }

    #if DEBUG
    /// Labels the item, for the keychain probe: an update in place keeps the label, a delete and
    /// add drops it. An update query carries no return keys.
    static func probeMark(for account: String) -> Bool {
        SecItemUpdate(query(for: account) as CFDictionary, [kSecAttrLabel as String: "octo-walk-probe-marker"] as CFDictionary) == errSecSuccess
    }

    /// Whether the item still carries the probe's label.
    static func probeLabel(for account: String) -> Bool {
        var found = query(for: account)
        found[kSecReturnAttributes as String] = true
        found[kSecMatchLimit as String] = kSecMatchLimitOne
        var result: AnyObject?
        guard SecItemCopyMatching(found as CFDictionary, &result) == errSecSuccess,
              let attributes = result as? [String: Any]
        else { return false }
        return attributes[kSecAttrLabel as String] as? String == "octo-walk-probe-marker"
    }
    #endif
}

/// The keychain as OctoKit's keep order reads and writes it.
struct KeychainItems: SecretItems {
    func lookup(for account: String) -> SecretRead { Keychain.lookup(for: account) }

    func saveData(_ data: Data, for account: String) -> Bool { Keychain.saveData(data, for: account) }

    func delete(for account: String) { Keychain.delete(for: account) }
}

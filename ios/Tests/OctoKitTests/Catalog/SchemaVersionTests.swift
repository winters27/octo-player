import Foundation
import Testing
@testable import OctoKit

/// Opening a library database written by a newer Octo.
@Suite struct SchemaVersionTests {
    private func tempPath() -> String {
        FileManager.default.temporaryDirectory.appendingPathComponent("schema-\(UUID().uuidString).sqlite").path
    }

    private func tables(_ db: SQLiteDatabase) throws -> [String] {
        try db.strings("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name")
    }

    @Test func aVersionTwoFileOpenedByAVersionOneSchemaIsLeftAsItIs() throws {
        let path = tempPath()
        defer { for end in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + end) } }
        do {
            let db = try SQLiteDatabase(path: path)
            try db.execute("CREATE TABLE marker (x INTEGER); INSERT INTO marker VALUES (7);")
            db.userVersion = 2
        }
        do {
            let db = try SQLiteDatabase(path: path)
            #expect(throws: NewerCatalogFile(found: 2, known: 1)) {
                try Schema.open(db, version: 1, migrations: [:])
            }
        }
        let reopened = try SQLiteDatabase(path: path)
        #expect(reopened.userVersion == 2)
        #expect(try tables(reopened) == ["marker"])
        #expect(try reopened.scalar("SELECT x FROM marker") == 7)
    }

    @Test func aVersionTwoFileGainsTheFamilyColumns() throws {
        let path = tempPath()
        defer { for end in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + end) } }
        do {
            let db = try SQLiteDatabase(path: path)
            try db.execute("""
                CREATE TABLE playlist (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    serverId TEXT, sourceId TEXT, syncedAt INTEGER, syncedName TEXT, serverStamp TEXT, octoNotice TEXT);
                INSERT INTO playlist (id, name, createdAt, updatedAt) VALUES ('p', 'Mix', 1, 2);
                """)
            db.userVersion = 2
        }
        let db = try SQLiteDatabase(path: path)
        try Schema.open(db)
        #expect(db.userVersion == Schema.version)
        let row = try #require(try db.rows(PlaylistRow.self, "SELECT * FROM playlist").first)
        #expect(row.name == "Mix")
        #expect(!row.family)
        #expect(row.syncedSongs == nil)
    }

    @Test func aVersionThreeFileGainsTheFamilyRightsColumns() throws {
        let path = tempPath()
        defer { for end in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + end) } }
        do {
            let db = try SQLiteDatabase(path: path)
            try db.execute("""
                CREATE TABLE playlist (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    serverId TEXT, sourceId TEXT, syncedAt INTEGER, syncedName TEXT, serverStamp TEXT, octoNotice TEXT,
                    family INTEGER NOT NULL DEFAULT 0, syncedSongs TEXT);
                INSERT INTO playlist (id, name, createdAt, updatedAt, family) VALUES ('p', 'Mix', 1, 2, 1);
                """)
            db.userVersion = 3
        }
        let db = try SQLiteDatabase(path: path)
        try Schema.open(db)
        #expect(db.userVersion == Schema.version)
        let row = try #require(try db.rows(PlaylistRow.self, "SELECT * FROM playlist").first)
        #expect(row.name == "Mix")
        #expect(row.family)
        #expect(!row.familyMayAdd && !row.familyMayRemove && !row.familyMayRename && !row.familyMayDelete)
    }

    @Test func aVersionFourFileGainsTheMarksAndTheFolderTablesAndKeepsItsBases() throws {
        let path = tempPath()
        defer { for end in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + end) } }
        do {
            let db = try SQLiteDatabase(path: path)
            try db.execute("""
                CREATE TABLE playlist (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
                    serverId TEXT, sourceId TEXT, syncedAt INTEGER, syncedName TEXT, serverStamp TEXT, octoNotice TEXT,
                    family INTEGER NOT NULL DEFAULT 0, syncedSongs TEXT, familyMayAdd INTEGER NOT NULL DEFAULT 0,
                    familyMayRemove INTEGER NOT NULL DEFAULT 0, familyMayRename INTEGER NOT NULL DEFAULT 0,
                    familyMayDelete INTEGER NOT NULL DEFAULT 0);
                INSERT INTO playlist (id, name, createdAt, updatedAt, serverId, sourceId, syncedAt, family, syncedSongs)
                    VALUES ('p', 'Mix', 1, 2, 'pl9', 's', 2, 1, 'a');
                """)
            db.userVersion = 4
        }
        let db = try SQLiteDatabase(path: path)
        try Schema.open(db)
        #expect(db.userVersion == 5)
        let row = try #require(try db.rows(PlaylistRow.self, "SELECT * FROM playlist").first)
        #expect(row.family)
        #expect(row.syncedSongs == "a", "a family playlist keeps the base its family path wrote")
        #expect(row.upkeep == nil)
        #expect(!row.octoImport)
        let names = try tables(db)
        for table in ["folder_op", "playlist_base_mark", "playlist_folder", "playlist_place"] { #expect(names.contains(table)) }
    }

    @Test func openingTwiceStaysAtTheCurrentVersionAndKeepsRows() throws {
        let path = tempPath()
        defer { for end in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + end) } }
        do {
            let db = try SQLiteDatabase(path: path)
            try Schema.open(db)
            try db.execute("INSERT INTO playlist (id, name, createdAt, updatedAt) VALUES ('p', 'Mix', 1, 2)")
        }
        let db = try SQLiteDatabase(path: path)
        try Schema.open(db)
        #expect(db.userVersion == Schema.version)
        #expect(try db.rows(PlaylistRow.self, "SELECT * FROM playlist").map(\.id) == ["p"])
    }

    @Test func theStoreThrowsForANewerFileSoTheAppOpensAFreshOne() throws {
        let path = tempPath()
        defer { for end in ["", "-wal", "-shm"] { try? FileManager.default.removeItem(atPath: path + end) } }
        do {
            let db = try SQLiteDatabase(path: path)
            db.userVersion = Schema.version + 1
        }
        #expect(throws: NewerCatalogFile(found: Schema.version + 1, known: Schema.version)) {
            _ = try CatalogStore(path: path)
        }
        #expect(try SQLiteDatabase(path: path).userVersion == Schema.version + 1)
    }
}

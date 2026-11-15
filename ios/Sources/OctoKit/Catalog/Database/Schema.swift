import Foundation

/// A library database written by a newer Octo than this one: `found` is its version, `known` the
/// newest this build reads.
public struct NewerCatalogFile: Error, Equatable, Sendable {
    public let found: Int
    public let known: Int
}

/// The library database's tables. Raise `version` and add a step to `migrations` when a table
/// changes; a step runs once on a file made by the version before it.
enum Schema {
    static let version = 5

    static let create = """
    CREATE TABLE IF NOT EXISTS track (
        id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, nativeId TEXT NOT NULL, title TEXT NOT NULL,
        searchKey TEXT NOT NULL, sortKey TEXT NOT NULL, artist TEXT NOT NULL, artistId TEXT NOT NULL, album TEXT NOT NULL,
        albumId TEXT NOT NULL, trackNo INTEGER, discNo INTEGER, year INTEGER, durationMs INTEGER NOT NULL,
        addedAt INTEGER NOT NULL, mimeType TEXT, sizeBytes INTEGER, artwork TEXT, uri TEXT,
        albumOrder INTEGER NOT NULL DEFAULT 0, relinkKey TEXT NOT NULL DEFAULT '', genre TEXT NOT NULL DEFAULT '',
        onPhone INTEGER NOT NULL DEFAULT 1, rating INTEGER NOT NULL DEFAULT 0, explicit INTEGER);
    CREATE INDEX IF NOT EXISTS index_track_albumId ON track (albumId);
    CREATE INDEX IF NOT EXISTS index_track_artistId ON track (artistId);
    CREATE INDEX IF NOT EXISTS index_track_sourceId ON track (sourceId);
    CREATE INDEX IF NOT EXISTS index_track_sortKey ON track (sortKey);
    CREATE INDEX IF NOT EXISTS index_track_searchKey ON track (searchKey);
    CREATE INDEX IF NOT EXISTS index_track_relinkKey ON track (relinkKey);

    CREATE TABLE IF NOT EXISTS album (
        id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, nativeId TEXT NOT NULL, title TEXT NOT NULL,
        searchKey TEXT NOT NULL, sortKey TEXT NOT NULL, artist TEXT NOT NULL, artistId TEXT NOT NULL, year INTEGER,
        songCount INTEGER NOT NULL, durationMs INTEGER NOT NULL, addedAt INTEGER NOT NULL, artwork TEXT,
        releaseTypes TEXT NOT NULL DEFAULT '');
    CREATE INDEX IF NOT EXISTS index_album_artistId ON album (artistId);
    CREATE INDEX IF NOT EXISTS index_album_sourceId ON album (sourceId);
    CREATE INDEX IF NOT EXISTS index_album_sortKey ON album (sortKey);
    CREATE INDEX IF NOT EXISTS index_album_searchKey ON album (searchKey);
    CREATE INDEX IF NOT EXISTS index_album_addedAt ON album (addedAt);

    CREATE TABLE IF NOT EXISTS artist (
        id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, name TEXT NOT NULL, searchKey TEXT NOT NULL,
        sortKey TEXT NOT NULL, albumCount INTEGER NOT NULL, songCount INTEGER NOT NULL, artwork TEXT);
    CREATE INDEX IF NOT EXISTS index_artist_sourceId ON artist (sourceId);
    CREATE INDEX IF NOT EXISTS index_artist_sortKey ON artist (sortKey);
    CREATE INDEX IF NOT EXISTS index_artist_searchKey ON artist (searchKey);

    CREATE TABLE IF NOT EXISTS source_track (
        id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, nativeId TEXT NOT NULL, title TEXT NOT NULL,
        searchKey TEXT NOT NULL, sortKey TEXT NOT NULL, artist TEXT NOT NULL, artistId TEXT NOT NULL, album TEXT NOT NULL,
        albumId TEXT NOT NULL, trackNo INTEGER, discNo INTEGER, year INTEGER, durationMs INTEGER NOT NULL,
        addedAt INTEGER NOT NULL, mimeType TEXT, sizeBytes INTEGER, artwork TEXT, uri TEXT, albumOrder INTEGER NOT NULL,
        relinkKey TEXT NOT NULL, genre TEXT NOT NULL, bitrate INTEGER, sampleRate INTEGER, bitDepth INTEGER,
        playCount INTEGER, lastPlayedAt INTEGER, starredAt INTEGER, trackGain REAL, albumGain REAL, trackPeak REAL,
        albumPeak REAL, baseGain REAL, fallbackGain REAL, rating INTEGER, mergedId TEXT NOT NULL DEFAULT '',
        originalYear INTEGER, genres TEXT NOT NULL DEFAULT '', artists TEXT NOT NULL DEFAULT '', composer TEXT, bpm INTEGER,
        comment TEXT, explicit INTEGER, discTitle TEXT, mbRecordingId TEXT, mbAlbumId TEXT, mbReleaseGroupId TEXT,
        mbArtistIds TEXT NOT NULL DEFAULT '', personal INTEGER NOT NULL DEFAULT 0);
    CREATE INDEX IF NOT EXISTS index_source_track_sourceId ON source_track (sourceId);
    CREATE INDEX IF NOT EXISTS index_source_track_mergedId ON source_track (mergedId);
    CREATE INDEX IF NOT EXISTS index_source_track_nativeId ON source_track (sourceId, nativeId);

    CREATE TABLE IF NOT EXISTS source_album (
        id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, nativeId TEXT NOT NULL, title TEXT NOT NULL,
        searchKey TEXT NOT NULL, sortKey TEXT NOT NULL, artist TEXT NOT NULL, artistId TEXT NOT NULL, year INTEGER,
        songCount INTEGER NOT NULL, durationMs INTEGER NOT NULL, addedAt INTEGER NOT NULL, artwork TEXT,
        releaseTypes TEXT NOT NULL DEFAULT '');
    CREATE INDEX IF NOT EXISTS index_source_album_sourceId ON source_album (sourceId);

    CREATE TABLE IF NOT EXISTS source_artist (
        id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, name TEXT NOT NULL, searchKey TEXT NOT NULL,
        sortKey TEXT NOT NULL, albumCount INTEGER NOT NULL, songCount INTEGER NOT NULL, artwork TEXT);
    CREATE INDEX IF NOT EXISTS index_source_artist_sourceId ON source_artist (sourceId);

    CREATE TABLE IF NOT EXISTS file_tags (
        mediaId INTEGER NOT NULL PRIMARY KEY, modifiedAt INTEGER NOT NULL, size INTEGER NOT NULL, readOk INTEGER NOT NULL,
        title TEXT, artist TEXT, albumArtist TEXT, album TEXT, trackNo INTEGER, discNo INTEGER, year INTEGER,
        compilation INTEGER NOT NULL, mbAlbumId TEXT, genres TEXT NOT NULL DEFAULT '', tagsVersion INTEGER NOT NULL DEFAULT 0,
        originalYear INTEGER, artists TEXT NOT NULL DEFAULT '', composer TEXT, bpm INTEGER, comment TEXT, explicit INTEGER,
        discTitle TEXT, mbRecordingId TEXT, mbReleaseGroupId TEXT, mbArtistIds TEXT NOT NULL DEFAULT '', sortTitle TEXT,
        sortAlbum TEXT, sortAlbumArtist TEXT, trackGain REAL, albumGain REAL, trackPeak REAL, albumPeak REAL);

    CREATE TABLE IF NOT EXISTS liked_track (
        trackId TEXT NOT NULL PRIMARY KEY, relinkKey TEXT NOT NULL, likedAt INTEGER NOT NULL);

    CREATE TABLE IF NOT EXISTS track_rating (
        trackId TEXT NOT NULL PRIMARY KEY, relinkKey TEXT NOT NULL, rating INTEGER NOT NULL, ratedAt INTEGER NOT NULL);

    CREATE TABLE IF NOT EXISTS play_event (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, trackId TEXT NOT NULL, relinkKey TEXT NOT NULL,
        startedAt INTEGER NOT NULL, playedMs INTEGER NOT NULL, durationMs INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS index_play_event_trackId ON play_event (trackId);
    CREATE INDEX IF NOT EXISTS index_play_event_startedAt ON play_event (startedAt);

    CREATE TABLE IF NOT EXISTS playlist (
        id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL,
        serverId TEXT, sourceId TEXT, syncedAt INTEGER, syncedName TEXT, serverStamp TEXT, octoNotice TEXT,
        family INTEGER NOT NULL DEFAULT 0, syncedSongs TEXT, familyMayAdd INTEGER NOT NULL DEFAULT 0,
        familyMayRemove INTEGER NOT NULL DEFAULT 0, familyMayRename INTEGER NOT NULL DEFAULT 0,
        familyMayDelete INTEGER NOT NULL DEFAULT 0, upkeep TEXT, octoImport INTEGER NOT NULL DEFAULT 0);

    CREATE TABLE IF NOT EXISTS playlist_item (
        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, playlistId TEXT NOT NULL, trackId TEXT NOT NULL,
        relinkKey TEXT NOT NULL, position INTEGER NOT NULL, serverSongId TEXT,
        FOREIGN KEY(playlistId) REFERENCES playlist(id) ON UPDATE NO ACTION ON DELETE CASCADE);
    CREATE INDEX IF NOT EXISTS index_playlist_item_playlistId_position ON playlist_item (playlistId, position);

    CREATE TABLE IF NOT EXISTS queue_item (
        position INTEGER NOT NULL PRIMARY KEY, trackId TEXT NOT NULL, shuffledPosition INTEGER NOT NULL);

    CREATE TABLE IF NOT EXISTS queue_state (
        id INTEGER NOT NULL PRIMARY KEY, currentIndex INTEGER NOT NULL, positionMs INTEGER NOT NULL,
        repeatMode INTEGER NOT NULL, shuffle INTEGER NOT NULL, savedAt INTEGER NOT NULL);

    CREATE TABLE IF NOT EXISTS online_song (
        id TEXT NOT NULL PRIMARY KEY, sourceId TEXT NOT NULL, nativeId TEXT NOT NULL, title TEXT NOT NULL,
        artist TEXT NOT NULL, album TEXT NOT NULL, albumId TEXT, artistId TEXT, durationMs INTEGER NOT NULL, coverId TEXT,
        mimeType TEXT, bitrate INTEGER, seenAt INTEGER NOT NULL, requestedAt INTEGER NOT NULL DEFAULT 0,
        adoptedId TEXT NOT NULL DEFAULT '', explicit INTEGER);
    CREATE INDEX IF NOT EXISTS index_online_song_sourceId ON online_song (sourceId);
    CREATE INDEX IF NOT EXISTS index_online_song_requestedAt ON online_song (requestedAt);

    CREATE TABLE IF NOT EXISTS download (
        trackId TEXT NOT NULL, sourceId TEXT NOT NULL, serverId TEXT NOT NULL, path TEXT NOT NULL,
        sizeBytes INTEGER NOT NULL, format TEXT, state TEXT NOT NULL, progress REAL NOT NULL, addedAt INTEGER NOT NULL,
        reason TEXT NOT NULL, title TEXT NOT NULL, artist TEXT NOT NULL, fileSize INTEGER, PRIMARY KEY(sourceId, serverId));
    CREATE INDEX IF NOT EXISTS index_download_trackId ON download (trackId);
    CREATE INDEX IF NOT EXISTS index_download_state ON download (state);

    CREATE TABLE IF NOT EXISTS liked_album (
        albumId TEXT NOT NULL PRIMARY KEY, relinkKey TEXT NOT NULL, likedAt INTEGER NOT NULL);

    CREATE TABLE IF NOT EXISTS liked_artist (
        artistId TEXT NOT NULL PRIMARY KEY, relinkKey TEXT NOT NULL, likedAt INTEGER NOT NULL);

    CREATE TABLE IF NOT EXISTS pinned_item (
        kind TEXT NOT NULL, itemId TEXT NOT NULL, relinkKey TEXT NOT NULL, position INTEGER NOT NULL,
        PRIMARY KEY(kind, itemId));

    CREATE TABLE IF NOT EXISTS playlist_folder (
        space TEXT NOT NULL, id TEXT NOT NULL, name TEXT NOT NULL, parent TEXT, created TEXT NOT NULL,
        PRIMARY KEY(space, id));
    CREATE INDEX IF NOT EXISTS index_playlist_folder_space_parent ON playlist_folder (space, parent);

    CREATE TABLE IF NOT EXISTS playlist_place (
        space TEXT NOT NULL, playlistKey TEXT NOT NULL, folderId TEXT NOT NULL, localOnly INTEGER NOT NULL DEFAULT 0,
        PRIMARY KEY(space, playlistKey));
    CREATE INDEX IF NOT EXISTS index_playlist_place_space_folderId ON playlist_place (space, folderId);

    CREATE TABLE IF NOT EXISTS folder_op (
        seq INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, space TEXT NOT NULL, kind TEXT NOT NULL, folderId TEXT,
        name TEXT, parent TEXT, playlistKeys TEXT NOT NULL, at INTEGER NOT NULL);
    CREATE INDEX IF NOT EXISTS index_folder_op_space_seq ON folder_op (space, seq);

    CREATE TABLE IF NOT EXISTS playlist_base_mark (sourceId TEXT NOT NULL PRIMARY KEY, at INTEGER NOT NULL);
    """

    /// Steps from each older version to the next, by the version they start from.
    static let migrations: [Int: String] = [
        // Each download notes the server's size for the file it was made from.
        1: "ALTER TABLE download ADD COLUMN fileSize INTEGER;",
        // Playlists say when they are the family's, and keep the server's songs as last agreed.
        2: "ALTER TABLE playlist ADD COLUMN family INTEGER NOT NULL DEFAULT 0; ALTER TABLE playlist ADD COLUMN syncedSongs TEXT;",
        // Family playlists keep what this listener may do with them.
        3: """
        ALTER TABLE playlist ADD COLUMN familyMayAdd INTEGER NOT NULL DEFAULT 0; \
        ALTER TABLE playlist ADD COLUMN familyMayRemove INTEGER NOT NULL DEFAULT 0; \
        ALTER TABLE playlist ADD COLUMN familyMayRename INTEGER NOT NULL DEFAULT 0; \
        ALTER TABLE playlist ADD COLUMN familyMayDelete INTEGER NOT NULL DEFAULT 0;
        """,
        // Playlists carry the server's upkeep kind and whether they are an imported playlist's copy;
        // the folder tables and the base step's mark come with `create`. Nothing is copied or seeded.
        4: "ALTER TABLE playlist ADD COLUMN upkeep TEXT; ALTER TABLE playlist ADD COLUMN octoImport INTEGER NOT NULL DEFAULT 0;",
    ]

    /// Makes the tables, or brings a file from an older version up to `version`. A file written
    /// by a newer Octo is left exactly as it is and `NewerCatalogFile` is thrown, so the app
    /// opens a fresh catalog in its place, as it does for a file it cannot read. The work runs
    /// in one transaction, so an upgrade cut short leaves the file as it was.
    static func open(_ db: SQLiteDatabase, version: Int = Schema.version, migrations: [Int: String] = Schema.migrations) throws {
        let found = db.userVersion
        if found > version { throw NewerCatalogFile(found: found, known: version) }
        try db.execute("BEGIN IMMEDIATE")
        do {
            if found == 0 {
                try db.execute(create)
            } else {
                for step in found..<version {
                    if let sql = migrations[step] { try db.execute(sql) }
                }
                try db.execute(create)
            }
            try db.execute("PRAGMA user_version = \(version)")
            try db.execute("COMMIT")
        } catch {
            try? db.execute("ROLLBACK")
            throw error
        }
    }
}

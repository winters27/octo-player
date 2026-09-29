"""A pretend GitHub on this PC for rehearsing the desktop update: it answers
the one API call the app makes (the public repository's list of releases)
and serves each release's files, from a folder of staged releases.

  pretend_github.py --root DIR [--port 47631] [--log FILE] [--hours 3]

DIR holds one folder per release, named by its tag, with the files
release_assets.py staged there (installers, update.json, update.json.sig,
SHA256SUMS). An optional <tag>.md beside the folder is the release's notes.
A version with a suffix is marked a pre-release, as the real workflow marks
it. The list also carries one dated server release with no files, as the
real repository does, which the app must pass over.

Downloads answer the way GitHub's do: the listed address redirects to the
file. Only 127.0.0.1 is served, every request is logged as one line, and
the server stops by itself after --hours.

  pretend_github.py self-test
"""
import argparse
import datetime
import hashlib
import json
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

REPOSITORY = "winters27/octo"
# The server's own dated release, which shares the repository.
SERVER_RELEASE = {"tag_name": "2026.09.23", "name": "Octo 2026.09.23", "draft": False, "prerelease": False, "body": "The server.", "assets": []}


def releases(root: Path, base: str) -> list[dict]:
    found = []
    folders = sorted((p for p in root.iterdir() if p.is_dir() and not p.name.startswith(".")), key=lambda p: p.stat().st_mtime, reverse=True)
    for index, folder in enumerate(folders):
        tag = folder.name
        notes = root / f"{tag}.md"
        assets = []
        for number, path in enumerate(sorted(p for p in folder.iterdir() if p.is_file() and not p.name.startswith("."))):
            assets.append({
                "id": (index + 1) * 1000 + number,
                "name": path.name,
                "size": path.stat().st_size,
                "content_type": "application/octet-stream",
                "state": "uploaded",
                "browser_download_url": f"{base}{REPOSITORY}/releases/download/{urllib.parse.quote(tag)}/{urllib.parse.quote(path.name)}",
            })
        found.append({
            "id": index + 1,
            "tag_name": tag,
            "name": tag,
            "draft": False,
            "prerelease": "-" in tag.split("-v", 1)[-1],
            "body": notes.read_text(encoding="utf-8").strip() if notes.is_file() else "",
            "published_at": datetime.datetime.fromtimestamp(folder.stat().st_mtime, datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
            "assets": assets,
        })
    return found + [SERVER_RELEASE]


class Handler(BaseHTTPRequestHandler):
    server_version = "pretend-github"
    root: Path = Path(".")
    log_file: Path | None = None
    log_lock = threading.Lock()

    def log_message(self, format: str, *args) -> None:
        line = f"{datetime.datetime.now().strftime('%Y-%m-%d %H:%M:%S')} {self.address_string()} {format % args}"
        with self.log_lock:
            print(line, flush=True)
            if self.log_file is not None:
                with self.log_file.open("a", encoding="utf-8") as log:
                    log.write(line + "\n")

    def base(self) -> str:
        host = self.headers.get("Host") or f"127.0.0.1:{self.server.server_address[1]}"
        return f"http://{host}/"

    def send(self, code: int, body: bytes, kind: str = "application/json; charset=utf-8", headers: dict | None = None) -> None:
        self.send_response(code)
        self.send_header("Content-Type", kind)
        self.send_header("Content-Length", str(len(body)))
        for name, value in (headers or {}).items():
            self.send_header(name, value)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(body)

    def not_found(self) -> None:
        self.send(404, json.dumps({"message": "Not Found"}).encode())

    def do_HEAD(self) -> None:
        self.do_GET()

    def do_GET(self) -> None:
        url = urllib.parse.urlsplit(self.path)
        parts = [urllib.parse.unquote(p) for p in url.path.split("/") if p]
        owner, name = REPOSITORY.split("/")
        # The list of releases: GET /repos/<owner>/<repo>/releases?per_page=100
        if parts == ["repos", owner, name, "releases"]:
            body = json.dumps(releases(self.root, self.base()), indent=2).encode()
            etag = '"' + hashlib.sha256(body).hexdigest()[:32] + '"'
            if self.headers.get("If-None-Match") == etag:
                self.send_response(304)
                self.send_header("ETag", etag)
                self.end_headers()
                return
            self.send(200, body, headers={"ETag": etag, "X-RateLimit-Remaining": "59"})
        # A release file as the release lists it, which redirects as GitHub's do.
        elif len(parts) == 6 and parts[:4] == [owner, name, "releases", "download"]:
            if not self.file(parts[4], parts[5]):
                return self.not_found()
            target = f"/assets/{urllib.parse.quote(parts[4])}/{urllib.parse.quote(parts[5])}"
            self.send(302, b"", "text/plain", {"Location": target})
        elif len(parts) == 3 and parts[0] == "assets":
            path = self.file(parts[1], parts[2])
            if not path:
                return self.not_found()
            self.send_response(200)
            self.send_header("Content-Type", "application/octet-stream")
            self.send_header("Content-Length", str(path.stat().st_size))
            self.end_headers()
            if self.command != "HEAD":
                with path.open("rb") as stream:
                    while block := stream.read(1 << 20):
                        self.wfile.write(block)
        elif not parts:
            self.send(200, json.dumps({"pretend": "github", "repository": REPOSITORY, "releases": f"{self.base()}repos/{REPOSITORY}/releases"}).encode())
        else:
            self.not_found()

    # A staged file, or None: only names inside a release's own folder.
    def file(self, tag: str, name: str) -> Path | None:
        if "/" in tag or "\\" in tag or "/" in name or "\\" in name or tag.startswith(".") or name.startswith("."):
            return None
        path = self.root / tag / name
        return path if path.is_file() else None


def serve(root: Path, port: int, log: Path | None, hours: float) -> ThreadingHTTPServer:
    Handler.root = root
    Handler.log_file = log
    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    if hours > 0:
        stop = threading.Timer(hours * 3600, server.shutdown)
        stop.daemon = True
        stop.start()
    return server


def self_test() -> None:
    import tempfile
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        release = root / "desktop-v1.1.0-rehearsal.2"
        release.mkdir()
        (release / "Octo-1.1.0-rehearsal.2-windows-x64.msi").write_bytes(b"x" * 5000)
        (release / "update.json").write_bytes(b"{}")
        (root / "desktop-v1.1.0-rehearsal.2.md").write_text("- Notes.", encoding="utf-8")
        server = serve(root, 0, None, 0)
        Handler.log_message = lambda *args: None
        threading.Thread(target=server.serve_forever, daemon=True).start()
        base = f"http://127.0.0.1:{server.server_address[1]}/"
        with urllib.request.urlopen(f"{base}repos/{REPOSITORY}/releases?per_page=100") as answer:
            etag = answer.headers["ETag"]
            listed = json.loads(answer.read())
        assert [r["tag_name"] for r in listed] == ["desktop-v1.1.0-rehearsal.2", "2026.09.23"], listed
        assert listed[0]["prerelease"] and not listed[1]["prerelease"] and listed[0]["body"] == "- Notes."
        msi = next(a for a in listed[0]["assets"] if a["name"].endswith(".msi"))
        assert msi["size"] == 5000
        with urllib.request.urlopen(msi["browser_download_url"]) as answer:
            assert answer.read() == b"x" * 5000 and answer.url.startswith(f"{base}assets/")
        request = urllib.request.Request(f"{base}repos/{REPOSITORY}/releases?per_page=100", headers={"If-None-Match": etag})
        try:
            urllib.request.urlopen(request)
            raise AssertionError("an unchanged list was sent again")
        except urllib.error.HTTPError as error:
            assert error.code == 304
        for missing in ["repos/someone/else/releases", f"{REPOSITORY}/releases/download/desktop-v1.1.0-rehearsal.2/..%2Fdesktop-v1.1.0-rehearsal.2.md"]:
            try:
                urllib.request.urlopen(base + missing)
                raise AssertionError(missing)
            except urllib.error.HTTPError as error:
                assert error.code == 404, missing
        server.shutdown()
        server.server_close()
    print("pretend_github self-test passed")


def main() -> None:
    if sys.argv[1:] == ["self-test"]:
        return self_test()
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--root", type=Path, required=True, help="the folder of staged releases")
    parser.add_argument("--port", type=int, default=47631)
    parser.add_argument("--log", type=Path, help="also append each request line to this file")
    parser.add_argument("--hours", type=float, default=3, help="stop after this long (0: never)")
    args = parser.parse_args()
    if not args.root.is_dir():
        sys.exit(f"pretend_github: {args.root} is not a folder")
    server = serve(args.root.resolve(), args.port, args.log, args.hours)
    print(f"pretend GitHub at http://127.0.0.1:{args.port}/ serving {args.root}", flush=True)
    try:
        server.serve_forever()
    finally:
        server.server_close()
    print("pretend GitHub stopped", flush=True)


if __name__ == "__main__":
    main()

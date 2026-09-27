"""Uploads the project to Google Drive after each build: one folder per day with that day's latest files.

Drive layout (created by this script, so it only ever touches its own files):
    Fils – Project/
        Versions/
            2026-09-26/
                Android-project-v2.1.zip
                iPhone-Fils-project-v1.0.zip
                Fils-v2.1-build27.apk   (builds from main only)
                Changes.txt

Files with the same name in the same day's folder are replaced, so each day keeps the day's latest version.

Needs three repository secrets (see README → "Google Drive copies"):
    GDRIVE_CLIENT_ID, GDRIVE_CLIENT_SECRET, GDRIVE_REFRESH_TOKEN
Usage: python3 drive_upload.py <day folder name> <file> [<file> …]
"""
import json
import mimetypes
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

ROOT_NAME = "Fils – Project"
VERSIONS_NAME = "Versions"
FOLDER = "application/vnd.google-apps.folder"
API = "https://www.googleapis.com/drive/v3"
UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"


def request(method, url, token=None, data=None, headers=None, raw=False):
    h = dict(headers or {})
    if token:
        h["Authorization"] = f"Bearer {token}"
    body = data
    if isinstance(data, (dict, list)):
        body = json.dumps(data).encode()
        h.setdefault("Content-Type", "application/json; charset=UTF-8")
    req = urllib.request.Request(url, data=body, method=method, headers=h)
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            if raw:
                return r
            text = r.read().decode() or "{}"
            return json.loads(text)
    except urllib.error.HTTPError as e:
        detail = e.read().decode(errors="replace")[:500]
        raise SystemExit(f"Google Drive said {e.code} for {method} {url.split('?')[0]}: {detail}")


def access_token():
    form = urllib.parse.urlencode({
        "client_id": os.environ["GDRIVE_CLIENT_ID"],
        "client_secret": os.environ["GDRIVE_CLIENT_SECRET"],
        "refresh_token": os.environ["GDRIVE_REFRESH_TOKEN"],
        "grant_type": "refresh_token",
    }).encode()
    r = request("POST", "https://oauth2.googleapis.com/token", data=form,
                headers={"Content-Type": "application/x-www-form-urlencoded"})
    return r["access_token"]


def q(value):
    return value.replace("\\", "\\\\").replace("'", "\\'")


def find(token, name, parent=None, folder=False):
    clauses = [f"name = '{q(name)}'", "trashed = false"]
    if folder:
        clauses.append(f"mimeType = '{FOLDER}'")
    if parent:
        clauses.append(f"'{parent}' in parents")
    params = urllib.parse.urlencode({"q": " and ".join(clauses), "fields": "files(id,name)", "spaces": "drive"})
    return request("GET", f"{API}/files?{params}", token)["files"]


def folder(token, name, parent=None):
    found = find(token, name, parent, folder=True)
    if found:
        return found[0]["id"]
    meta = {"name": name, "mimeType": FOLDER}
    if parent:
        meta["parents"] = [parent]
    return request("POST", f"{API}/files?fields=id", token, meta)["id"]


def upload(token, path, parent):
    name = os.path.basename(path)
    for old in find(token, name, parent):
        request("DELETE", f"{API}/files/{old['id']}", token)
    mime = mimetypes.guess_type(name)[0] or "application/octet-stream"
    if name.endswith(".apk"):
        mime = "application/vnd.android.package-archive"
    size = os.path.getsize(path)
    # Resumable upload: works for any size.
    r = request("POST", f"{UPLOAD}?uploadType=resumable&fields=id", token, {"name": name, "parents": [parent]},
                headers={"X-Upload-Content-Type": mime, "X-Upload-Content-Length": str(size)}, raw=True)
    session = r.headers["Location"]
    with open(path, "rb") as f:
        request("PUT", session, token, f.read(), headers={"Content-Type": mime, "Content-Length": str(size)})
    print(f"Uploaded {name} ({size // 1024} KB)")


def main():
    if len(sys.argv) < 3:
        raise SystemExit("usage: drive_upload.py <day folder> <file> [<file> …]")
    day, files = sys.argv[1], sys.argv[2:]
    token = access_token()
    root = folder(token, ROOT_NAME)
    versions = folder(token, VERSIONS_NAME, root)
    day_folder = folder(token, day, versions)
    for p in files:
        if os.path.exists(p):
            upload(token, p, day_folder)
    print(f"::notice title=Google Drive::Uploaded {len(files)} file(s) to {ROOT_NAME}/{VERSIONS_NAME}/{day}")


if __name__ == "__main__":
    main()

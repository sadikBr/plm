"""Client for the PLM connector protocol (roadmap 8.5), standard library only (Python 3.8+).

A connector (FreeCAD, KiCad, a script) saves what its tool wrote with one manifest, and opens a
model by downloading it with everything it depends on:

    plm = PlmConnector("http://localhost:8080", token)
    upload = plm.upload("Bracket.FCStd")
    saved = plm.save({"contextId": product, "application": "FreeCAD 1.0", "models": [
        {"key": "Bracket.FCStd", "name": "Bracket", "kind": "PART", "uploadId": upload}]})
    files = plm.open(saved[0]["id"], "/tmp/bracket")   # the file and its dependencies

The protocol is described in connectors/README.md.
"""
import hashlib
import json
import os
import urllib.error
import urllib.request

PROTOCOL = 1


class PlmError(Exception):
    def __init__(self, message, code=None, status=None):
        super().__init__(message)
        self.code = code
        self.status = status


class PlmConnector:
    def __init__(self, server, token):
        self.server = server.rstrip("/")
        self.token = token

    def _url(self, path):
        return path if path.startswith("http") else self.server + path

    def _request(self, method, path, body=None, raw=None, content_type=None, auth=True):
        data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
        req = urllib.request.Request(self._url(path), data=data, method=method)
        if auth:
            req.add_header("Authorization", "Bearer " + self.token)
        if content_type or body is not None:
            req.add_header("Content-Type", content_type or "application/json")
        try:
            with urllib.request.urlopen(req) as res:
                return res.read()
        except urllib.error.HTTPError as e:
            text = e.read().decode(errors="replace")
            try:
                problem = json.loads(text)
            except ValueError:
                problem = {}
            raise PlmError(problem.get("detail") or f"{method} {path}: {e.code}", problem.get("code"), e.code) from None

    def get(self, path):
        return json.loads(self._request("GET", path) or b"null")

    def post(self, path, body):
        return json.loads(self._request("POST", path, body=body) or b"null")

    def check(self):
        """The server speaks this protocol version (or a newer, compatible one)."""
        info = self.get("/api/v1/connectors")
        if info.get("protocol", 0) < PROTOCOL:
            raise PlmError("This server is too old for this connector")
        return info

    def upload(self, path, data=None):
        """Sends a file to the vault: the upload id to put in a manifest."""
        if data is None:
            with open(path, "rb") as f:
                data = f.read()
        ticket = self.post("/api/v1/vault/uploads", {"fileName": os.path.basename(path), "size": len(data)})
        self._request("PUT", ticket["uploadUrl"], raw=data, content_type="application/octet-stream", auth=False)
        return ticket["uploadId"]

    def save(self, manifest):
        """Creates or updates every model of the manifest in one go: all of them or none."""
        return self.post("/api/v1/connectors/save", manifest)

    def files(self, model_id):
        """A model and everything it depends on: what to download to open it."""
        return self.get(f"/api/v1/connectors/models/{model_id}/files")

    def open(self, model_id, folder):
        """Downloads a model and its dependencies into [folder], checking each file's SHA-256."""
        os.makedirs(folder, exist_ok=True)
        files = self.files(model_id)
        for f in files:
            if not f.get("url"):
                continue
            data = self._request("GET", f["url"], auth=False)
            if hashlib.sha256(data).hexdigest() != f["sha256"]:
                raise PlmError(f"{f['number']}: the download doesn't match its checksum")
            with open(os.path.join(folder, f["fileName"]), "wb") as out:
                out.write(data)
        return files

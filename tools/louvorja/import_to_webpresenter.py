#!/usr/bin/env python3
"""
Imports a LouvorJA export (louvorja-songs.json + media/, made by export_louvorja.py) into a
webpresenter server: uploads every audio and background once to the library (the server
de-duplicates by content) and creates or updates each song through the idempotent import API.

Conversion to the webpresenter song model (docs/songs.md):
  - slides are split into blocks at instrumental breaks (empty slides);
  - identical blocks become one part referenced several times in the singing order; the most
    repeated block is the chorus ("Coro"), the others are verses in order of appearance;
  - instrumental breaks reference one shared INSTRUMENTAL part;
  - slide start times become the timing of the SUNG recording, playback start times the timing of
    the PLAYBACK recording (cues out of time order are dropped);
  - albums become collections (number = track), grouped by their LouvorJA category.

Usage:
  WEBPRESENTER_PASSWORD=... import_to_webpresenter.py --export-dir DIR --server URL --user NAME
      [--keycloak URL] [--limit N] [--workers 4]

Progress is saved in DIR/import-state.json, so an interrupted run resumes where it stopped.
"""
import argparse
import collections
import concurrent.futures
import json
import os
import sys
import threading
import time
from pathlib import Path

import requests

PURPOSE_BY_KIND = {"audio": "SONG_AUDIO", "background": "SONG_BACKGROUND"}


class Api:
    """Authenticated calls; refreshes the access token before it expires."""

    def __init__(self, server, keycloak, user, password, client="webpresenter-cli"):
        self.server = server.rstrip("/")
        self.token_url = f"{keycloak.rstrip('/')}/realms/webpresenter/protocol/openid-connect/token"
        self.client = client
        self.lock = threading.Lock()
        self.session = requests.Session()
        self._token(dict(grant_type="password", username=user, password=password))

    def _token(self, form):
        r = requests.post(self.token_url, data=dict(client_id=self.client, **form), timeout=30)
        r.raise_for_status()
        data = r.json()
        self.access = data["access_token"]
        self.refresh = data.get("refresh_token")
        self.expires = time.time() + data.get("expires_in", 300) - 30

    def headers(self):
        with self.lock:
            if time.time() > self.expires:
                self._token(dict(grant_type="refresh_token", refresh_token=self.refresh))
            return {"Authorization": f"Bearer {self.access}"}

    def upload(self, path, purpose):
        with open(path, "rb") as f:
            r = self.session.post(f"{self.server}/api/media", headers=self.headers(),
                                  files={"file": (path.name, f)}, data={"purpose": purpose, "deduplicate": "true"},
                                  timeout=600)
        if r.status_code not in (200, 201):
            raise RuntimeError(f"upload {path.name}: HTTP {r.status_code} {r.text[:200]}")
        return r.json()["id"]

    def import_song(self, payload):
        r = self.session.post(f"{self.server}/api/songs/import", headers=self.headers(), json=payload, timeout=60)
        if r.status_code != 200:
            raise RuntimeError(f"import '{payload['song']['title']}': HTTP {r.status_code} {r.text[:300]}")
        return r.json()


# ---------------------------------------------------------------------- conversion

def convert_lyrics(slides):
    """LouvorJA flat slides -> (parts, order, sequence) where sequence[i] is the source slide i."""
    blocks = []          # list of ("text", [slides]) or ("instrumental", [slide])
    current = []
    for s in slides:
        if not s["text"]:
            if current:
                blocks.append(("text", current))
                current = []
            blocks.append(("instrumental", [s]))
        else:
            current.append(s)
    if current:
        blocks.append(("text", current))

    def key(block):
        return tuple((s["text"], s["cue"]) for s in block[1])

    counts = collections.Counter(key(b) for b in blocks if b[0] == "text")
    chorus_key = None
    if counts:
        best, n = counts.most_common(1)[0]
        if n > 1:
            chorus_key = best

    parts, order, ids = [], [], {}
    verse = 0
    for kind, block in blocks:
        if kind == "instrumental":
            if "i" not in ids.values():
                parts.append({"id": "i", "kind": "INSTRUMENTAL", "label": "Instrumental",
                              "slides": [{"lines": [], "cue": None}]})
                ids["instrumental"] = "i"
            order.append("i")
            continue
        k = key((kind, block))
        if k not in ids:
            if k == chorus_key:
                pid, label, pkind = "c", "Coro", "CHORUS"
            elif counts[k] > 1:
                pid, label, pkind = f"c{len(ids)}", f"Coro {len([x for x in parts if x['kind'] == 'CHORUS']) + 1}", "CHORUS"
            else:
                verse += 1
                pid, label, pkind = f"v{verse}", f"Estrofe {verse}", "VERSE"
            ids[k] = pid
            parts.append({"id": pid, "kind": pkind, "label": label,
                          "slides": [{"lines": s["text"].split("\n"), "cue": s["cue"]} for s in block]})
        order.append(ids[k])
    return parts, order


def cues(slides, field):
    """Slide index -> start in ms, keeping only cues that move forward in time."""
    result, last = [], -1
    for index, s in enumerate(slides):
        seconds = s[field]
        if seconds is None:
            if index == 0 and field == "start":
                seconds = 0.0
            else:
                continue
        ms = int(round(seconds * 1000))
        if ms <= last:
            continue
        result.append({"slide": index, "startMs": ms})
        last = ms
    return result


def most_common_background(song):
    paths = [s["background"]["path"] for s in song["slides"] if s.get("background") and s["background"].get("available")]
    if paths:
        return collections.Counter(paths).most_common(1)[0][0]
    bg = song.get("background")
    return bg["path"] if bg and bg.get("available") else None


# ---------------------------------------------------------------------- main

def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--export-dir", required=True, type=Path)
    p.add_argument("--server", required=True)
    p.add_argument("--keycloak", help="default: <server>/auth")
    p.add_argument("--user", required=True)
    p.add_argument("--limit", type=int)
    p.add_argument("--workers", type=int, default=4)
    args = p.parse_args()

    password = os.environ.get("WEBPRESENTER_PASSWORD")
    if not password:
        sys.exit("set WEBPRESENTER_PASSWORD")
    api = Api(args.server, args.keycloak or f"{args.server.rstrip('/')}/auth", args.user, password)

    data = json.loads((args.export_dir / "louvorja-songs.json").read_text(encoding="utf-8"))
    state_file = args.export_dir / "import-state.json"
    state = json.loads(state_file.read_text()) if state_file.exists() else {"media": {}, "songs": {}}
    state_lock = threading.Lock()

    def save_state():
        with state_lock:
            tmp = state_file.with_suffix(".tmp")
            tmp.write_text(json.dumps(state))
            tmp.replace(state_file)

    categories = {c["id"]: c["name"] for c in data["collections"]}
    albums = {a["id"]: a for a in data["albums"]}
    songs = data["songs"][: args.limit] if args.limit else data["songs"]

    # 1. Media: every file the selected songs need, uploaded once.
    needed = {}
    for song in songs:
        for kind in ("sung", "playback"):
            ref = song["audio"][kind]
            if ref and ref.get("available"):
                needed[ref["path"]] = "audio"
        bg = most_common_background(song)
        if bg:
            needed[bg] = "background"
    todo = [(path, kind) for path, kind in needed.items() if path not in state["media"]]
    print(f"songs: {len(songs)}, media needed: {len(needed)}, already uploaded: {len(needed) - len(todo)}", flush=True)

    done = 0
    failures = []
    started = time.time()

    def upload(item):
        path, kind = item
        media_id = api.upload(args.export_dir / path, PURPOSE_BY_KIND[kind])
        with state_lock:
            state["media"][path] = media_id
        return path

    with concurrent.futures.ThreadPoolExecutor(args.workers) as pool:
        futures = {pool.submit(upload, item): item for item in todo}
        for future in concurrent.futures.as_completed(futures):
            try:
                future.result()
            except Exception as e:  # keep going; failures are listed at the end
                failures.append(str(e))
            done += 1
            if done % 100 == 0 or done == len(todo):
                save_state()
                rate = done / max(1, time.time() - started)
                print(f"  uploaded {done}/{len(todo)} ({rate:.1f}/s)", flush=True)
    save_state()

    # 2. Songs.
    imported = skipped = 0
    for song in songs:
        slides = song["slides"]
        if not slides:
            skipped += 1
            continue
        parts, order = convert_lyrics(slides)
        recordings = []
        for kind, field in (("SUNG", "start"), ("PLAYBACK", "playbackStart")):
            ref = song["audio"]["sung" if kind == "SUNG" else "playback"]
            media_id = state["media"].get(ref["path"]) if ref else None
            if kind == "PLAYBACK" and any(r["mediaId"] == media_id for r in recordings):
                media_id = None  # a "playback" identical to the sung file is a data error in the source
            if media_id:
                timing_cues = cues(slides, field)
                recordings.append({"kind": kind, "mediaId": media_id, "source": "IMPORT",
                                   "timing": {"source": "IMPORT", "reviewed": True, "cues": timing_cues}
                                   if timing_cues else None})
        collections_input = []
        for entry in song["albums"]:
            album = albums.get(entry["albumId"])
            if not album:
                continue
            group = next((categories[c] for c in album["collectionIds"] if c in categories), None)
            collections_input.append({"slug": f"louvorja-{album['id']}", "name": album["name"], "group": group,
                                      "number": str(entry["track"]) if entry.get("track") else None})
        background = most_common_background(song)
        payload = {
            "origin": "louvorja",
            "externalId": str(song["id"]),
            "song": {
                "title": song["title"],
                "language": song["language"],
                "status": "READY",
                "backgroundMediaId": state["media"].get(background) if background else None,
                "lyrics": {"parts": parts, "order": order},
                "collections": collections_input,
            },
            "recordings": recordings,
        }
        try:
            result = api.import_song(payload)
            state["songs"][str(song["id"])] = result["id"]
            imported += 1
        except Exception as e:
            failures.append(str(e))
        if imported % 200 == 0:
            save_state()
            print(f"  songs imported {imported}/{len(songs)}", flush=True)
    save_state()
    print(f"done: {imported} songs imported, {skipped} without slides, {len(failures)} failures "
          f"in {time.time() - started:.0f}s", flush=True)
    for f in failures[:20]:
        print("  FAILED", f)


if __name__ == "__main__":
    main()

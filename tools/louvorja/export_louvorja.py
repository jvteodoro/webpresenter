#!/usr/bin/env python3
"""
Exports songs, albums, collections and timed lyric slides from a LouvorJA database
(config/database.db) to a neutral JSON file that webpresenter can import.

The database contains copyrighted lyrics: keep exports for local use by the church that
licensed LouvorJA. Do not commit exported data to this repository.

Usage: export_louvorja.py <database.db> <output-dir> [<LouvorJA config dir>]

With the config dir (where LouvorJA stores downloaded files), every downloaded file is
hard-linked into <output-dir>/media/<server path> (no extra disk space on the same filesystem)
and marked "available" in the JSON.
"""
import json
import os
import sqlite3
import sys
from pathlib import Path

# Server path -> local path, from LouvorJA's own download table (filled in main()).
LOCAL_FILES = {}
MEDIA_OUT = None


def seconds(hms):
    """'00:01:02' -> 62.0 ; None/'00:00:00' -> None."""
    if not hms:
        return None
    parts = [float(p) for p in hms.replace(",", ".").split(":")]
    total = 0.0
    for p in parts:
        total = total * 60 + p
    return total or None


def file_ref(row):
    if row is None or row["id_file"] is None:
        return None
    path = f'{row["dir"].rstrip("/")}/{row["file_name"]}'.lstrip("/")
    ref = {
        "path": f"media/{path}",
        "sizeBytes": row["size"],
        "durationSeconds": seconds(row["duration"]),
    }
    if MEDIA_OUT is not None:
        ref["available"] = link_media(path, row["size"])
    return ref


def link_media(path, size):
    """Hard-links the downloaded file into the export; True if present with the expected size."""
    local = LOCAL_FILES.get(path)
    if local is None or not local.is_file() or local.stat().st_size != size:
        return False
    target = MEDIA_OUT / path
    if not target.exists():
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            os.link(local, target)
        except OSError:  # different filesystem: fall back to a copy
            import shutil
            shutil.copy2(local, target)
    return True


def main(db_path, out_dir, config_dir=None):
    global MEDIA_OUT
    db = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
    db.row_factory = sqlite3.Row
    if config_dir:
        config = Path(config_dir)
        MEDIA_OUT = Path(out_dir) / "media"
        for r in db.execute("select URL, URL_DOWNLOAD from ARQUIVOS_SISTEMA where URL_DOWNLOAD is not null"):
            relative = r["URL"].replace("\\", "/")
            relative = relative[len("config/"):] if relative.startswith("config/") else relative
            LOCAL_FILES[r["URL_DOWNLOAD"]] = config / relative
    files = {r["id_file"]: r for r in db.execute("select * from files")}

    collections = [
        {"id": r["id_category"], "name": r["name"], "slug": r["slug"], "order": r["order"]}
        for r in db.execute('select * from categories order by "order", name')
    ]
    collections_by_album = {}
    for r in db.execute("select id_album, id_category from categories_albums"):
        collections_by_album.setdefault(r["id_album"], []).append(r["id_category"])
    albums = [
        {
            "id": r["id_album"],
            "name": r["name"],
            "color": r["color"] or None,
            "cover": file_ref(files.get(r["id_file_image"])),
            "collectionIds": collections_by_album.get(r["id_album"], []),
        }
        for r in db.execute("select * from albums order by name")
    ]

    tracks = {}
    for r in db.execute("select id_music, id_album, track from albums_musics"):
        tracks.setdefault(r["id_music"], []).append({"albumId": r["id_album"], "track": r["track"]})

    slides_by_song = {}
    for r in db.execute('select * from lyrics order by id_music, "order"'):
        if not r["show_slide"]:
            continue  # hidden in LouvorJA
        text = (r["lyric"] or "").replace("\r\n", "\n").strip()
        slides_by_song.setdefault(r["id_music"], []).append({
            "text": text,                                   # empty = instrumental break
            "cue": (r["aux_lyric"] or "").strip() or None,  # e.g. "Homens:"
            "start": seconds(r["time"]),                    # in the sung recording
            "playbackStart": seconds(r["instrumental_time"]),  # in the playback recording
            "background": file_ref(files.get(r["id_file_image"])),
        })

    songs = []
    for r in db.execute("select * from musics order by name"):
        songs.append({
            "id": r["id_music"],
            "title": r["name"].strip(),
            "language": r["id_language"],
            "albums": tracks.get(r["id_music"], []),
            "audio": {
                "sung": file_ref(files.get(r["id_file_music"])),
                "playback": file_ref(files.get(r["id_file_instrumental_music"])),
            },
            "background": file_ref(files.get(r["id_file_image"])),
            "slides": slides_by_song.get(r["id_music"], []),
        })

    version = db.execute("select * from VERSAO").fetchone()
    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    data = {
        "source": {"app": "LouvorJA", "databaseVersion": version[1] if version else None},
        "collections": collections,
        "albums": albums,
        "songs": songs,
    }
    (out / "louvorja-songs.json").write_text(json.dumps(data, ensure_ascii=False, indent=1), encoding="utf-8")

    timed = sum(1 for s in songs if any(sl["start"] for sl in s["slides"]))
    if MEDIA_OUT is not None:
        refs = [r for s in songs for r in (s["audio"]["sung"], s["audio"]["playback"], s["background"],
                                            *[sl["background"] for sl in s["slides"]]) if r]
        unique = {r["path"]: r for r in refs}
        ok = sum(1 for r in unique.values() if r["available"])
        print(f"media files referenced: {len(unique)}, available: {ok}, missing: {len(unique) - ok}")
    print(f"collections: {len(collections)}, albums: {len(albums)}, songs: {len(songs)}, "
          f"slides: {sum(len(s['slides']) for s in songs)}, songs with timed slides: {timed}, "
          f"songs with playback: {sum(1 for s in songs if s['audio']['playback'])}")


if __name__ == "__main__":
    if len(sys.argv) not in (3, 4):
        sys.exit(__doc__)
    main(*sys.argv[1:])

"""Download a fixed public Hugging Face snapshot and emit a SHA-256 manifest."""

import hashlib
import json
import sys
from pathlib import Path

from huggingface_hub import snapshot_download


MODEL_ID = "Qwen/Qwen2.5-7B-Instruct"
REVISION = "a09a35458c702b33eeacc393d103063234e8bc28"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(8 * 1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> None:
    output = Path(sys.argv[1])
    snapshot_download(
        repo_id=MODEL_ID,
        revision=REVISION,
        local_dir=output,
        max_workers=8,
    )
    files = []
    for path in sorted(output.rglob("*")):
        if not path.is_file() or ".cache" in path.parts:
            continue
        files.append({
            "path": path.relative_to(output).as_posix(),
            "bytes": path.stat().st_size,
            "sha256": sha256_file(path),
        })
    manifest = {
        "model": MODEL_ID,
        "revision": REVISION,
        "license": "apache-2.0",
        "totalBytes": sum(item["bytes"] for item in files),
        "files": files,
    }
    (output / "MANIFEST.sha256.json").write_text(
        json.dumps(manifest, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps({"model": MODEL_ID, "revision": REVISION,
                      "fileCount": len(files), "totalBytes": manifest["totalBytes"]}))


if __name__ == "__main__":
    main()

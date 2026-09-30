#!/usr/bin/env python3
"""Preserve AFL filenames (including colons) inside an upload-safe archive."""
from pathlib import Path
import sys
import tarfile


def archive_results(root):
    root = Path(root)
    results = [root / name for name in ("fuzz-nkem", "fuzz-nkpr")
               if (root / name).is_dir()]
    if not results:
        print("No fuzz output was created; there are no samples to archive")
        return None
    destination = root / "parser-fuzz-results.tar.gz"
    with tarfile.open(destination, "w:gz") as archive:
        for result in results:
            archive.add(result, arcname=result.name)
    return destination


if __name__ == "__main__":
    archive_results(sys.argv[1])

"""Stage user-supplied knowledge snapshots; never publish or write to the application DB."""

import argparse
import hashlib
import json
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
CORPUS = ROOT / "data/knowledge"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def stage(source):
    packages = []
    pending_copies = []
    for bundle in sorted(source.iterdir()):
        if not bundle.is_dir():
            continue
        manifests = list((bundle / "data/knowledge").glob("*manifest.json"))
        if len(manifests) != 1:
            raise ValueError(f"Expected one source manifest: {bundle.name}")
        manifest = json.loads(manifests[0].read_text(encoding="utf-8"))
        if manifest.get("status", manifest.get("review_status")) != "DRAFT":
            raise ValueError(f"Only DRAFT packages can be staged: {bundle.name}")
        licences = list(bundle.glob("LICENSE-ATTRIBUTION*.md"))
        if len(licences) != 1:
            raise ValueError(f"Missing or ambiguous attribution: {bundle.name}")
        package_root = Path("packages") / bundle.name
        files = []
        articles = []
        for item in sorted(bundle.rglob("*")):
            if not item.is_file():
                continue
            if item.is_symlink() or item.suffix not in {".md", ".json", ".jsonl", ".txt", ".pdf"}:
                raise ValueError(f"Unsupported source file: {item}")
            data = item.read_bytes()
            sha = digest(data)
            relative = item.relative_to(bundle)
            binary = item.suffix == ".pdf"
            destination = (
                ROOT / "infrastructure/data/knowledge" / f"{sha}.pdf"
                if binary else CORPUS / package_root / relative
            )
            if item.suffix == ".json":
                value = json.loads(data.decode("utf-8"))
                if "curated" in relative.parts and item.name.endswith(".metadata.json"):
                    if value.get("status") != "DRAFT" or value.get("retrieval_enabled") is not False:
                        raise ValueError(f"Unexpected publication state: {item}")
                    article = item.with_name(item.name.replace(".metadata.json", ".md"))
                    if not article.is_file():
                        raise ValueError(f"Missing curated text: {article}")
                    articles.append({
                        "document_id": value["document_id"],
                        "version": value["version"],
                        "metadata_path": relative.as_posix(),
                        "content_path": article.relative_to(bundle).as_posix(),
                        "content_sha256": digest(article.read_bytes()),
                        "content_role": value["content_role"],
                    })
            elif item.suffix == ".jsonl":
                for line in data.decode("utf-8").splitlines():
                    if line.strip():
                        json.loads(line)
            files.append({
                "path": relative.as_posix(), "sha256": sha, "bytes": len(data),
                "storage": "LOCAL_OBJECT_STAGING" if binary else "REPOSITORY_TEXT",
                **({"local_staging_path": destination.relative_to(ROOT).as_posix(),
                    "object_key": None} if binary else {}),
            })
            pending_copies.append((item, destination, sha))
        packages.append({
            "source_id": manifest["source_id"], "package_path": package_root.as_posix(),
            "manifest_path": manifests[0].relative_to(bundle).as_posix(),
            "attribution_path": licences[0].relative_to(bundle).as_posix(),
            "status": "DRAFT", "retrieval_enabled": False,
            "files": files, "articles": articles,
        })
    if not packages:
        raise ValueError("No knowledge packages found")
    index = {"format_version": 1, "purpose": "F01_PREPARATION",
             "status": "DRAFT", "retrieval_enabled": False, "packages": packages}
    output = json.dumps(index, ensure_ascii=False, indent=2) + "\n"
    index_path = CORPUS / "package-index.json"
    # Preflight every destination before writing. Existing user files are never overwritten.
    if index_path.exists() and index_path.read_text(encoding="utf-8") != output:
        raise ValueError("Package index differs; import a reviewed new version separately")
    for _, destination, sha in pending_copies:
        if destination.exists() and digest(destination.read_bytes()) != sha:
            raise ValueError(f"Destination differs; refusing overwrite: {destination}")
    for item, destination, _ in pending_copies:
        if not destination.exists():
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(item, destination)
    index_path.write_text(output, encoding="utf-8", newline="\n")
    print(f"Staged {len(packages)} DRAFT packages; retrieval remains disabled.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    stage(parser.parse_args().source.resolve(strict=True))

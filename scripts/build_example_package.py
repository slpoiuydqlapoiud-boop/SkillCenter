from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "examples" / "skills" / "summarize-release-notes"
OUTPUT = ROOT / "examples" / "packages" / "summarize-release-notes-1.0.0.zip"
INCLUDED_FILES = ("SKILL.md", "skill.json", "agents/openai.yaml")


def build_package():
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with ZipFile(OUTPUT, "w", compression=ZIP_DEFLATED) as archive:
        for relative_name in INCLUDED_FILES:
            source_file = SOURCE / relative_name
            archive.write(source_file, f"{SOURCE.name}/{relative_name}")
    return OUTPUT


if __name__ == "__main__":
    print(build_package())

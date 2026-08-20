import copy
import json
import re
import unittest
import zipfile
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker


ROOT = Path(__file__).resolve().parents[2]
SCHEMA_DIR = ROOT / "contracts" / "schemas" / "v1"
VALID_EXAMPLE_DIR = ROOT / "contracts" / "examples" / "valid"
INVALID_EXAMPLE_DIR = ROOT / "contracts" / "examples" / "invalid"
SKILL_EXAMPLE_DIR = ROOT / "examples" / "skills" / "summarize-release-notes"
SKILL_ZIP = ROOT / "examples" / "packages" / "summarize-release-notes-1.0.0.zip"

CONTRACTS = {
    "skill": (SCHEMA_DIR / "skill.schema.json", SKILL_EXAMPLE_DIR / "skill.json"),
    "install-manifest": (
        SCHEMA_DIR / "install-manifest.schema.json",
        VALID_EXAMPLE_DIR / "install-manifest.json",
    ),
    "installation-event": (
        SCHEMA_DIR / "installation-event.schema.json",
        VALID_EXAMPLE_DIR / "installation-event.json",
    ),
    "invocation-event": (
        SCHEMA_DIR / "invocation-event.schema.json",
        VALID_EXAMPLE_DIR / "invocation-event.json",
    ),
}


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def validator_for(contract_name: str):
    schema_path, _ = CONTRACTS[contract_name]
    schema = read_json(schema_path)
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema)


class SchemaContractTests(unittest.TestCase):
    def assert_invalid(self, validator, payload, expected_fragment):
        errors = sorted(validator.iter_errors(payload), key=lambda item: list(item.path))
        self.assertTrue(errors, "payload unexpectedly matched the contract")
        rendered = "\n".join(
            f"/{'/'.join(map(str, error.path))}: {error.message}" for error in errors
        )
        self.assertIn(expected_fragment, rendered)

    def test_all_m0_schemas_and_canonical_examples_exist(self):
        for contract_name, (schema_path, example_path) in CONTRACTS.items():
            with self.subTest(contract=contract_name):
                self.assertTrue(schema_path.exists(), f"missing {contract_name} schema")
                self.assertTrue(example_path.exists(), f"missing {contract_name} example")

    def test_all_m0_schemas_are_valid_json_schema_2020_12(self):
        for contract_name in CONTRACTS:
            with self.subTest(contract=contract_name):
                validator_for(contract_name)

    def test_canonical_examples_validate(self):
        for contract_name, (_, example_path) in CONTRACTS.items():
            with self.subTest(contract=contract_name):
                validator = validator_for(contract_name)
                errors = list(validator.iter_errors(read_json(example_path)))
                self.assertEqual(errors, [], [error.message for error in errors])

    def test_skill_rejects_unstable_identity_version_and_unknown_fields(self):
        validator = validator_for("skill")
        valid_skill = read_json(CONTRACTS["skill"][1])

        invalid_id = {**valid_skill, "id": "Release_Notes"}
        self.assert_invalid(validator, invalid_id, "does not match")

        invalid_version = {**valid_skill, "version": "1.0"}
        self.assert_invalid(validator, invalid_version, "does not match")

        numeric_prerelease_with_leading_zero = {**valid_skill, "version": "1.0.0-01"}
        self.assert_invalid(
            validator, numeric_prerelease_with_leading_zero, "does not match"
        )

        leaked_content = {**valid_skill, "prompt": "secret input"}
        self.assert_invalid(validator, leaked_content, "prompt")

    def test_install_manifest_rejects_bad_hash_and_bearer_token_field(self):
        validator = validator_for("install-manifest")
        valid_manifest = read_json(CONTRACTS["install-manifest"][1])

        bad_hash = copy.deepcopy(valid_manifest)
        bad_hash["artifact"]["sha256"] = "abc"
        self.assert_invalid(validator, bad_hash, "does not match")

        leaked_token = {**valid_manifest, "bearerToken": "do-not-embed-secrets"}
        self.assert_invalid(validator, leaked_token, "bearerToken")

    def test_installation_event_enforces_failure_and_transition_fields(self):
        validator = validator_for("installation-event")
        valid_event = read_json(CONTRACTS["installation-event"][1])

        failed_without_code = {**valid_event, "outcome": "failure"}
        self.assert_invalid(validator, failed_without_code, "errorCode")

        upgrade_without_from_version = {**valid_event, "action": "upgrade"}
        self.assert_invalid(validator, upgrade_without_from_version, "fromVersion")

        success_with_error = {**valid_event, "errorCode": "UNEXPECTED"}
        self.assert_invalid(validator, success_with_error, "errorCode")

    def test_invocation_event_rejects_content_and_requires_failure_code(self):
        validator = validator_for("invocation-event")
        valid_event = read_json(CONTRACTS["invocation-event"][1])

        self.assert_invalid(
            validator, {**valid_event, "prompt": "customer content"}, "prompt"
        )
        self.assert_invalid(
            validator, {**valid_event, "output": "model response"}, "output"
        )
        self.assert_invalid(
            validator, {**valid_event, "status": "failure"}, "errorCode"
        )
        self.assert_invalid(validator, {**valid_event, "durationMs": -1}, "-1")

    def test_event_contracts_assert_uuid_and_rfc3339_formats(self):
        schema = read_json(CONTRACTS["invocation-event"][0])
        validator = Draft202012Validator(schema, format_checker=FormatChecker())
        valid_event = read_json(CONTRACTS["invocation-event"][1])

        self.assert_invalid(validator, {**valid_event, "eventId": "not-a-uuid"}, "not-a-uuid")
        self.assert_invalid(validator, {**valid_event, "occurredAt": "2026-08-12"}, "2026-08-12")

    def test_skill_example_has_codex_compatible_frontmatter(self):
        skill_file = SKILL_EXAMPLE_DIR / "SKILL.md"
        text = skill_file.read_text(encoding="utf-8")
        self.assertTrue(text.startswith("---\n"))
        frontmatter = text.split("---", 2)[1]
        self.assertIn("\nname: summarize-release-notes\n", frontmatter)
        self.assertIn("\ndescription:", frontmatter)
        frontmatter_name = re.search(r"^name:\s*(.+)$", frontmatter, re.MULTILINE).group(1)
        metadata = read_json(SKILL_EXAMPLE_DIR / "skill.json")
        self.assertEqual(frontmatter_name, metadata["id"])

    def test_committed_invalid_examples_are_rejected(self):
        cases = [
            ("skill", "skill-invalid-version.json", "does not match"),
            ("install-manifest", "install-manifest-bad-hash.json", "does not match"),
            ("installation-event", "installation-event-failure-missing-code.json", "errorCode"),
            ("invocation-event", "invocation-event-content-leak.json", "prompt"),
        ]
        for contract_name, file_name, expected_fragment in cases:
            with self.subTest(case=file_name):
                self.assert_invalid(
                    validator_for(contract_name),
                    read_json(INVALID_EXAMPLE_DIR / file_name),
                    expected_fragment,
                )

    def test_example_zip_has_one_safe_root_and_required_files(self):
        self.assertTrue(SKILL_ZIP.exists(), "missing canonical example ZIP")
        with zipfile.ZipFile(SKILL_ZIP) as archive:
            names = archive.namelist()
            self.assertTrue(names)
            self.assertTrue(all("\\" not in name for name in names))
            self.assertTrue(all(not name.startswith("/") for name in names))
            self.assertTrue(all(".." not in Path(name).parts for name in names))
            roots = {name.split("/", 1)[0] for name in names}
            self.assertEqual(roots, {"summarize-release-notes"})
            self.assertIn("summarize-release-notes/SKILL.md", names)
            self.assertIn("summarize-release-notes/skill.json", names)
            packaged_skill = json.loads(
                archive.read("summarize-release-notes/skill.json").decode("utf-8")
            )
            errors = list(validator_for("skill").iter_errors(packaged_skill))
            self.assertEqual(errors, [], [error.message for error in errors])


if __name__ == "__main__":
    unittest.main()

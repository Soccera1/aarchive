"""Exercise rolling publication without contacting GitHub or pushing real tags."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

PROJECT = Path(__file__).resolve().parents[1]
SHA = "a" * 40

MOCK = """#!/usr/bin/env python3
import json, os, pathlib, sys
command = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with open(os.environ['COMMAND_LOG'], 'a') as log:
    log.write(json.dumps([command, *args]) + '\\n')
if command == 'git':
    assert args == ['push', 'origin', os.environ['GITHUB_SHA'] + ':refs/tags/master', '--force']
elif args[0] == 'api':
    print(os.environ['LATEST_SHA'])
elif args[:2] == ['release', 'view']:
    sys.exit(0 if os.environ['EXISTING_RELEASE'] == '1' else 1)
elif args[:2] in (['release', 'create'], ['release', 'edit']):
    notes = pathlib.Path(args[args.index('--notes-file') + 1]).read_text()
    pathlib.Path(os.environ['NOTES_CAPTURE']).write_text(notes)
elif args[:2] == ['release', 'upload']:
    sys.exit(int(os.environ.get('UPLOAD_EXIT', '0')))
else:
    raise SystemExit('Unexpected command: ' + str(args))
"""


class MasterReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        for name in ("git", "gh"):
            command = self.bin / name
            command.write_text(MOCK)
            command.chmod(0o755)
        self.dist = self.root / "dist"
        self.dist.mkdir()
        self.apk = self.dist / "aarchive-master-universal.apk"
        self.apk.write_bytes(b"publication test fixture")
        self.checksum = self.dist / (self.apk.name + ".sha256")
        self.checksum.write_text(hashlib.sha256(self.apk.read_bytes()).hexdigest() + "  " + self.apk.name + "\n")
        self.log = self.root / "commands.jsonl"
        self.notes = self.root / "notes.md"

    def publish(self, existing=False, latest=SHA, upload_exit=0):
        env = dict(os.environ, PATH=str(self.bin) + os.pathsep + os.environ["PATH"],
                   GITHUB_REPOSITORY="example/aarchive", GITHUB_SHA=SHA,
                   GITHUB_RUN_ID="42", GITHUB_RUN_NUMBER="17", LATEST_SHA=latest,
                   EXISTING_RELEASE="1" if existing else "0", COMMAND_LOG=str(self.log),
                   NOTES_CAPTURE=str(self.notes), UPLOAD_EXIT=str(upload_exit))
        return subprocess.run(["bash", str(PROJECT / "scripts/publish-master-release.sh"), str(self.dist)],
                              env=env, text=True, capture_output=True)

    def commands(self):
        return [json.loads(line) for line in self.log.read_text().splitlines()]

    def test_first_push_creates_prerelease_with_both_assets(self):
        result = self.publish()
        self.assertEqual(result.returncode, 0, result.stderr)
        create = next(c for c in self.commands() if c[:3] == ["gh", "release", "create"])
        self.assertIn("--prerelease", create)
        self.assertIn("--latest=false", create)
        self.assertIn(str(self.apk), create)
        self.assertIn(str(self.checksum), create)
        self.assertIn(SHA, self.notes.read_text())
        self.assertIn("public development key", self.notes.read_text())

    def test_next_push_updates_existing_release_and_tag(self):
        result = self.publish(existing=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        commands = self.commands()
        self.assertIn(["git", "push", "origin", SHA + ":refs/tags/master", "--force"], commands)
        upload = next(c for c in commands if c[:3] == ["gh", "release", "upload"])
        self.assertIn("--clobber", upload)
        self.assertTrue(any(c[:3] == ["gh", "release", "edit"] for c in commands))
        self.assertFalse(any(c[:3] == ["gh", "release", "create"] for c in commands))

    def test_superseded_build_does_not_publish(self):
        result = self.publish(latest="b" * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.commands()), 1)
        self.assertIn("Skipping superseded", result.stdout)

    def test_corrupt_artifact_does_not_move_tag_or_publish(self):
        self.apk.write_bytes(b"corrupted")
        result = self.publish()
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.commands()), 1)

    def test_upload_failure_is_reported_without_updating_release_notes(self):
        result = self.publish(existing=True, upload_exit=1)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.notes.exists())


if __name__ == "__main__":
    unittest.main()

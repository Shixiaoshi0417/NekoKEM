#!/usr/bin/env python3
"""Exercise publication through a fake gh subprocess without remote mutations."""
import contextlib
import copy
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest import mock
from urllib.parse import parse_qs, urlparse

import publish_release as publish
import release_assets as assets
from test_release_assets import VERSION, SOURCE, RUN, build_release


REPO = 'Shixiaoshi0417/NekoKEM'
TAG = 'v' + VERSION
CI_RUN = '654321'
RELEASE_ID = 987
VERIFY_ASSETS = assets.verify


class FakeGh:
    def __init__(self, artifacts, notes):
        self.artifacts = artifacts
        self.notes = notes
        self.release = None
        self.pages = [[]]
        self.calls = []
        self.tagged = False
        self.wrong_digest = False
        self.fail_upload = None
        self.fail_recovery = False
        self.corrupt_published = False
        self.latest = None
        self.next_asset_id = 100

    def run(self, command, **kwargs):
        self.calls.append(command)
        if command[0] != 'gh':
            raise AssertionError(f'Unexpected subprocess: {command}')
        args = command[1:]
        if args[:2] == ['run', 'download']:
            shutil.copytree(self.artifacts, Path(args[args.index('--dir') + 1]))
            return subprocess.CompletedProcess(command, 0, '', '')
        if args[:2] == ['attestation', 'verify']:
            result = [{'verificationResult': {'signature': {'certificate': {
                'runInvocationURI': f'https://github.com/{REPO}/actions/runs/{RUN}/attempts/1'}}}}]
            return subprocess.CompletedProcess(command, 0, json.dumps(result), '')
        if args[0] != 'api':
            raise AssertionError(f'Publication must use the API by release ID: {command}')
        method = args[args.index('-X') + 1] if '-X' in args else 'GET'
        endpoint = next(arg for arg in args if arg.startswith(('repos/', 'https://uploads.')))
        fields = dict(arg.split('=', 1) for index, arg in enumerate(args)
                      if index and args[index - 1] in ('-f', '-F'))
        prefix = f'repos/{REPO}'
        if endpoint.startswith(f'{prefix}/actions/runs/'):
            run_id = endpoint.rsplit('/', 1)[1]
            name = 'Release' if run_id == RUN else 'CI'
            result = {'name': name, 'path': f'.github/workflows/{name.lower()}.yml',
                      'head_branch': 'main', 'head_repository': {'full_name': REPO},
                      'status': 'completed', 'conclusion': 'success', 'head_sha': SOURCE}
        elif endpoint.startswith(f'{prefix}/contents/'):
            return subprocess.CompletedProcess(command, 0, self.notes, '')
        elif endpoint.startswith(f'{prefix}/git/ref/tags/'):
            if not self.tagged:
                return subprocess.CompletedProcess(command, 1, '', 'HTTP 404: Not Found')
            result = {'object': {'type': 'commit', 'sha': SOURCE}}
        elif endpoint == f'{prefix}/releases?per_page=100':
            if '--paginate' not in args or '--slurp' not in args:
                raise AssertionError('Release listing must cover every page')
            pages = copy.deepcopy(self.pages)
            if self.release:
                pages[-1].append(copy.deepcopy(self.release))
            result = pages
        elif endpoint == f'{prefix}/releases' and method == 'POST':
            self.release = self.draft(Path(fields['body'][1:]).read_text())
            result = self.release
        elif endpoint.startswith(f'{prefix}/releases/assets/') and method == 'DELETE':
            asset_id = int(endpoint.rsplit('/', 1)[1])
            self.release['assets'] = [asset for asset in self.release['assets'] if asset['id'] != asset_id]
            result = None
        elif endpoint == f'{prefix}/releases/{RELEASE_ID}':
            if method == 'PATCH':
                if fields.get('draft') == 'true' and self.fail_recovery:
                    raise subprocess.CalledProcessError(1, command, stderr='recovery unavailable')
                self.release['draft'] = fields['draft'] == 'true'
                if 'make_latest' in fields:
                    self.latest = fields['make_latest']
                    self.tagged = True
            result = copy.deepcopy(self.release)
            if self.corrupt_published and not result['draft']:
                result['assets'][0]['digest'] = 'sha256:broken'
        elif endpoint.startswith(f'https://uploads.github.com/{prefix}/releases/{RELEASE_ID}/assets?'):
            path = Path(args[args.index('--input') + 1])
            name = parse_qs(urlparse(endpoint).query)['name'][0]
            if self.fail_upload == name:
                raise subprocess.CalledProcessError(1, command, stderr='upload interrupted')
            digest = 'sha256:' + assets.sha256(path)
            self.next_asset_id += 1
            result = {'id': self.next_asset_id, 'name': name, 'state': 'uploaded',
                      'digest': 'sha256:broken' if self.wrong_digest else digest}
            self.release['assets'].append(result)
        else:
            raise AssertionError(f'Unexpected API call: {command}')
        return subprocess.CompletedProcess(command, 0, json.dumps(result), '')

    @staticmethod
    def draft(body):
        return {'id': RELEASE_ID, 'draft': True, 'prerelease': False, 'tag_name': TAG,
                'target_commitish': SOURCE, 'body': body, 'assets': [],
                'html_url': f'https://github.com/{REPO}/releases/tag/{TAG}'}


class PublishTests(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.artifacts = self.root / 'artifacts'
        build_release(self.artifacts)
        self.notes = f'# Release\n{assets.ZH_PLACEHOLDER}\n---\n{assets.EN_PLACEHOLDER}\n'
        hashes = assets.collect(self.artifacts, self.root / 'reference')
        self.filled_notes = assets.fill_notes(self.notes, SOURCE, CI_RUN, RUN, hashes)
        self.fake = FakeGh(self.artifacts, self.notes)
        self.stderr = io.StringIO()

    def run_publish(self, **environment):
        env = {'REPO': REPO, 'TAG': TAG, 'CI_RUN': CI_RUN, 'RELEASE_RUN': RUN,
               'RUNNER_TEMP': str(self.root), 'GITHUB_STEP_SUMMARY': '', **environment}
        with mock.patch.dict(os.environ, env), \
                mock.patch.object(publish.subprocess, 'run', side_effect=self.fake.run), \
                mock.patch.object(publish.release_assets, 'verify', wraps=self.verify_assets), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(self.stderr):
            publish.main()

    @staticmethod
    def verify_assets(artifacts, version, source, run):
        return VERIFY_ASSETS(artifacts, version, source, run, apk_check=lambda *args: None)

    def test_normal_publication_uploads_and_edits_by_known_id(self):
        self.run_publish()
        self.assertFalse(self.fake.release['draft'])
        self.assertEqual(self.fake.latest, 'true')
        self.assertEqual(len(self.fake.release['assets']), len(assets.PACKAGES) + 1)
        self.assertEqual(self.fake.release['body'], self.filled_notes)

    def test_existing_partial_draft_resumes_without_reuploading(self):
        self.fake.release = self.fake.draft(self.filled_notes)
        existing = self.root / 'reference' / assets.PACKAGES[0]
        self.fake.release['assets'] = [{'id': 1, 'name': existing.name, 'state': 'uploaded',
                                      'digest': 'sha256:' + assets.sha256(existing)}]
        self.run_publish()
        self.assertFalse(self.fake.release['draft'])
        uploads = [call for call in self.fake.calls if '--input' in call]
        self.assertEqual(len(uploads), len(assets.PACKAGES))

    def test_published_release_is_never_modified(self):
        self.fake.release = self.fake.draft(self.filled_notes)
        self.fake.release['draft'] = False
        with self.assertRaisesRegex(assets.ReleaseError, 'already published'):
            self.run_publish()
        self.assertFalse(any('-X' in call for call in self.fake.calls))

    def test_digest_mismatch_keeps_release_draft(self):
        self.fake.wrong_digest = True
        with self.assertRaisesRegex(assets.ReleaseError, 'Uploaded assets differ'):
            self.run_publish()
        self.assertTrue(self.fake.release['draft'])
        self.assertIsNone(self.fake.latest)
        self.assertIn(f'Release ID {RELEASE_ID} is a draft', self.stderr.getvalue())

    def test_existing_draft_with_wrong_digest_is_not_replaced(self):
        self.fake.release = self.fake.draft(self.filled_notes)
        self.fake.release['assets'] = [{'id': 1, 'name': assets.PACKAGES[0], 'state': 'uploaded',
                                      'digest': 'sha256:broken'}]
        with self.assertRaisesRegex(assets.ReleaseError, 'Existing draft assets differ'):
            self.run_publish()
        self.assertFalse(any('--input' in call for call in self.fake.calls))

    def test_incomplete_starter_asset_is_deleted_by_id_and_uploaded_again(self):
        self.fake.release = self.fake.draft(self.filled_notes)
        self.fake.release['assets'] = [{'id': 7, 'name': assets.PACKAGES[0],
                                      'state': 'starter', 'digest': None}]
        self.run_publish()
        deleted = [call for call in self.fake.calls if 'DELETE' in call]
        self.assertEqual(len(deleted), 1)
        self.assertIn(f'repos/{REPO}/releases/assets/7', deleted[0])
        self.assertFalse(self.fake.release['draft'])
        self.assertEqual(len(self.fake.release['assets']), len(assets.PACKAGES) + 1)

    def test_later_page_newer_version_preserves_latest(self):
        self.fake.pages = [[{'tag_name': f'v1.0.{number}', 'draft': False, 'prerelease': False}
                            for number in range(100)],
                           [{'tag_name': 'v5.0.0', 'draft': False, 'prerelease': False}]]
        self.run_publish()
        self.assertEqual(self.fake.latest, 'false')

    def test_newer_draft_or_prerelease_does_not_change_latest_selection(self):
        self.fake.pages = [[{'tag_name': 'v5.0.0', 'draft': True, 'prerelease': False},
                            {'tag_name': 'v5.0.0', 'draft': False, 'prerelease': True}]]
        self.run_publish()
        self.assertEqual(self.fake.latest, 'true')

    def test_interrupted_upload_can_be_resumed(self):
        self.fake.fail_upload = 'NekoKEM-linux-aarch64-GUI.deb'
        with self.assertRaises(subprocess.CalledProcessError):
            self.run_publish()
        self.assertTrue(self.fake.release['draft'])
        self.assertTrue(self.fake.release['assets'])
        self.fake.fail_upload = None
        self.run_publish()
        self.assertFalse(self.fake.release['draft'])

    def test_postpublication_verification_failure_restores_draft(self):
        self.fake.corrupt_published = True
        with self.assertRaisesRegex(assets.ReleaseError, 'Published assets differ'):
            self.run_publish()
        self.assertTrue(self.fake.release['draft'])

    def test_failed_recovery_reports_known_release_and_uncertain_state(self):
        self.fake.corrupt_published = True
        self.fake.fail_recovery = True
        with self.assertRaisesRegex(assets.ReleaseError, 'Published assets differ'):
            self.run_publish()
        self.assertIn(f'Release ID {RELEASE_ID}: draft recovery failed', self.stderr.getvalue())
        self.assertIn('publication state is unconfirmed', self.stderr.getvalue())

    def test_unicode_digits_are_refused_before_subprocess_work(self):
        for environment in ({'TAG': 'v４.2.0'}, {'CI_RUN': '١٢٣'}, {'RELEASE_RUN': '１２３'}):
            with self.subTest(environment=environment):
                with self.assertRaises(assets.ReleaseError):
                    self.run_publish(**environment)
                self.assertEqual(self.fake.calls, [])


if __name__ == '__main__':
    unittest.main()

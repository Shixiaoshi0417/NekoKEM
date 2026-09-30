#!/usr/bin/env python3
"""Publish immutable, tested artifacts only after local signing recovery succeeds."""
import hashlib
import json
from pathlib import Path
import re
import shutil
import subprocess

REPO = 'Shixiaoshi0417/NekoKEM'
TAG = 'v3.2.0'
def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()
def api(path):
    return json.loads(run('gh', 'api', f'repos/{REPO}/{path}'))
def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
request = json.loads(Path('release/publish-request.json').read_text())
sha = request['source_sha']
if not re.fullmatch('[0-9a-f]{40}', sha) or request['tag'] != TAG:
    raise SystemExit('Invalid source/tag')
if request.get('signing_recovery_verified') is not True:
    raise SystemExit('Signing-key recovery must be verified before publication')
subprocess.run(['git', 'merge-base', '--is-ancestor', sha, 'origin/main'], check=True)
# Every executable workflow/script and the notes must match the tested source.
for path in ['.github/scripts/publish_release.py', '.github/workflows/publish-release.yml', 'release/v3.2.0.md']:
    if run('git', 'show', f'{sha}:{path}') != Path(path).read_text().strip():
        raise SystemExit('Publish implementation differs from tested source')
specifications = [
    ('ci_run_id', '.github/workflows/ci.yml',
     {'Linux Core and CLI tests','Android debug build and tests',
      'Android device tests (API 26)','Android device tests (API 35)'}),
    ('linux_run_id', '.github/workflows/linux-release.yml',
     {'Build and test x86_64','Build and test aarch64'}),
    ('android_run_id', '.github/workflows/android-release.yml', {'signed-apk'}),
]
links = []
for key, workflow, names in specifications:
    run_id = request[key]
    if not isinstance(run_id, int) or run_id <= 0:
        raise SystemExit('Invalid run ID')
    execution = api(f'actions/runs/{run_id}')
    if (execution['head_sha'] != sha or execution['path'] != workflow or
        execution['status'] != 'completed' or execution['conclusion'] != 'success' or
        execution['repository']['full_name'] != REPO):
        raise SystemExit(f'Unverified run: {run_id}')
    jobs = api(f'actions/runs/{run_id}/jobs?per_page=100')['jobs']
    if {j['name'] for j in jobs} != names or any(j['conclusion'] != 'success' for j in jobs):
        raise SystemExit(f'Missing or unsuccessful jobs: {run_id}')
    links.append(execution['html_url'])
downloads = Path('release-downloads')
downloads.mkdir()
for arch in ['x86_64','aarch64']:
    subprocess.run(['gh', 'run', 'download', str(request['linux_run_id']), '--repo', REPO,
                    '--name', f'NekoKEM-linux-{arch}', '--dir', str(downloads/arch)], check=True)
subprocess.run(['gh', 'run', 'download', str(request['android_run_id']), '--repo', REPO,
                '--name', 'NekoKEM-android-signed', '--dir', str(downloads/'android')], check=True)
metadata = json.loads((downloads/'android/build-metadata.json').read_text())
if (metadata['source_sha'] != sha or metadata['run_id'] != request['android_run_id'] or
    metadata['version'] != '3.2.0' or metadata['version_code'] != 4 or
    metadata['signer_sha256'] != request['signer_sha256'] or
    metadata['apk_sha256'] != request['apk_sha256'] or
    metadata['signing_key_rotated'] is not True):
    raise SystemExit('APK provenance/signing recovery mismatch')
assets = Path('release-assets')
assets.mkdir()
for path in [downloads/'android/app-release.apk',
             downloads/'x86_64/NekoKEM-linux-x86_64.tar.gz',
             downloads/'aarch64/NekoKEM-linux-aarch64.tar.gz']:
    if not path.is_file() or path.stat().st_size == 0:
        raise SystemExit('Missing release asset')
    shutil.copyfile(path, assets/path.name)
if digest(assets/'app-release.apk') != metadata['apk_sha256']:
    raise SystemExit('APK digest mismatch')
hashes = ''.join(f'{digest(path)}  {path.name}\n' for path in sorted(assets.iterdir()))
(assets/'SHA256SUMS.txt').write_text(hashes)
notes = Path('release/v3.2.0.md').read_text()
for language, title, source, verification, old, new in [
    ('ZH','构建与校验结果','源码提交','实际通过的验证','旧 APK 签名证书 SHA-256','新 APK 签名证书 SHA-256'),
    ('EN','Build and verification results','Source commit','Actually passed verification','Previous APK signing certificate SHA-256','New APK signing certificate SHA-256')]:
    detail = f'### {title}\n\n{source}: `{sha}`\n\n{verification}:\n\n'
    detail += '\n'.join(f'- {url}' for url in links)
    detail += f'\n\n{old}: `{metadata["previous_signer_sha256"]}`\n\n{new}: `{metadata["signer_sha256"]}`\n\n```text\n{hashes}```\n'
    notes = notes.replace(f'<!-- RELEASE_{language}_METADATA -->', detail)
Path('release-notes-published.md').write_text(notes)
refs = api('git/matching-refs/tags/v3.2.0')
exact = [ref for ref in refs if ref['ref'] == 'refs/tags/' + TAG]
if exact:
    if len(exact) != 1 or exact[0]['object']['type'] != 'commit' or exact[0]['object']['sha'] != sha:
        raise SystemExit('Existing release tag points to a different source')
else:
    subprocess.run(['gh', 'api', '--method', 'POST', f'repos/{REPO}/git/refs',
                    '-f', 'ref=refs/tags/'+TAG, '-f', 'sha='+sha], check=True)
releases = api('releases?per_page=100')
existing = [release for release in releases if release['tag_name'] == TAG]
if existing and (len(existing) != 1 or not existing[0]['draft']):
    raise SystemExit('A published release must never be overwritten')
if not existing:
    subprocess.run(['gh', 'release', 'create', TAG, '--repo', REPO, '--verify-tag', '--draft',
                    '--title', 'NekoKEM '+TAG, '--notes-file', 'release-notes-published.md'], check=True)
release = json.loads(run('gh','release','view',TAG,'--repo',REPO,'--json','assets,isDraft'))
if not release['isDraft']:
    raise SystemExit('Release must remain draft until all uploads are verified')
expected = {path.name: path for path in assets.iterdir()}
if any(asset['name'] not in expected for asset in release['assets']):
    raise SystemExit('Unexpected draft assets')
for name, path in expected.items():
    if not any(asset['name'] == name for asset in release['assets']):
        subprocess.run(['gh','release','upload',TAG,str(path),'--repo',REPO], check=True)
verified = Path('release-verified-downloads')
subprocess.run(['gh','release','download',TAG,'--repo',REPO,'--dir',str(verified)], check=True)
if {p.name for p in verified.iterdir()} != set(expected):
    raise SystemExit('Incomplete published asset set')
for name, path in expected.items():
    if digest(path) != digest(verified/name):
        raise SystemExit('Uploaded asset checksum mismatch')
subprocess.run(['gh','release','edit',TAG,'--repo',REPO,'--notes-file','release-notes-published.md',
                '--draft=false','--latest'], check=True)
print(f'https://github.com/{REPO}/releases/tag/{TAG}')

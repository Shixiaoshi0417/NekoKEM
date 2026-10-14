#!/usr/bin/env python3
"""Publish one verified Release run as a GitHub Release (Publish workflow).

Inputs come from the environment: REPO, TAG (vX.Y.Z), CI_RUN and
RELEASE_RUN. The CI and Release runs must have succeeded on the same main
commit. Every public asset must pass release_assets.verify and carry a
build provenance attestation from that Release run before a draft is
created, its upload is checked digest by digest, and only then published.
"""
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_assets  # noqa: E402

RELEASE_WORKFLOW = '.github/workflows/release.yml'
CI_WORKFLOW = '.github/workflows/ci.yml'


def gh(*args, **kwargs):
    return subprocess.run(['gh', *args], check=True, capture_output=True, text=True, **kwargs).stdout


def api(path):
    return json.loads(gh('api', path))


def list_releases(repo):
    """Include drafts and releases older than the first API page."""
    pages = json.loads(gh('api', '--paginate', '--slurp',
                         f'repos/{repo}/releases?per_page=100'))
    return [release for page in pages for release in page]


def semantic_version(tag):
    match = re.fullmatch(r'v([0-9]+)\.([0-9]+)\.([0-9]+)', tag)
    return tuple(int(part) for part in match.groups()) if match else None


def is_highest_version(tag, releases):
    version = semantic_version(tag)
    return all(semantic_version(release['tag_name']) <= version
               for release in releases if not release['draft'] and
               not release['prerelease'] and semantic_version(release['tag_name']) is not None)


def restore_draft(repo, release_id):
    """Report recovery failure without hiding the original publication error."""
    endpoint = f'repos/{repo}/releases/{release_id}'
    try:
        gh('api', '-X', 'PATCH', endpoint, '-F', 'draft=true')
        release_assets.require(api(endpoint)['draft'], 'GitHub did not restore draft status')
    except Exception as error:
        print(f'Release ID {release_id}: draft recovery failed ({error}); '
              f'publication state is unconfirmed. Inspect {endpoint} before retrying.',
              file=sys.stderr)
    else:
        print(f'Release ID {release_id} is a draft; verified files remain available for retry.',
              file=sys.stderr)


def peel_tag(repo, tag):
    """The commit a tag names, or None while the tag does not exist."""
    probe = subprocess.run(['gh', 'api', f'repos/{repo}/git/ref/tags/{tag}'],
                           capture_output=True, text=True)
    if probe.returncode != 0:
        release_assets.require('404' in probe.stderr, f'Cannot read tag {tag}')
        return None
    target = json.loads(probe.stdout)['object']
    while target['type'] == 'tag':
        target = api(f"repos/{repo}/git/tags/{target['sha']}")['object']
    release_assets.require(target['type'] == 'commit', f'Tag {tag} does not name a commit')
    return target['sha']


def checked_run(repo, run_id, name, path):
    run = api(f'repos/{repo}/actions/runs/{run_id}')
    # The API may append @<ref> to the workflow path.
    release_assets.require(run['name'] == name and run['path'].split('@', 1)[0] == path,
                           f'Run {run_id} is not the {name} workflow')
    release_assets.require(run['head_branch'] == 'main' and
                           run['head_repository']['full_name'] == repo,
                           f'Run {run_id} did not build main of {repo}')
    release_assets.require(run['status'] == 'completed' and run['conclusion'] == 'success',
                           f'Run {run_id} did not succeed')
    return run['head_sha']


def check_attestation(repo, path, source, release_run):
    """The asset was built by this Release run from main at the source commit."""
    output = gh('attestation', 'verify', str(path), '--repo', repo,
                '--signer-workflow', f'{repo}/{RELEASE_WORKFLOW}',
                '--source-digest', source, '--source-ref', 'refs/heads/main',
                '--deny-self-hosted-runners', '--format', 'json')
    runs = set()
    for result in json.loads(output):
        verified = result.get('verificationResult', {})
        # The signing certificate names the run; the signed SLSA statement too.
        certificate = verified.get('signature', {}).get('certificate', {})
        runs.add(certificate.get('runInvocationURI', ''))
        details = verified.get('statement', {}).get('predicate', {}).get('runDetails', {})
        runs.add(details.get('metadata', {}).get('invocationId', ''))
    marker = f'/{repo}/actions/runs/{release_run}/'
    release_assets.require(any(marker in uri for uri in runs),
                           f'{path.name} is not attested by Release run {release_run}')


def plan_upload(existing, expected):
    """Return the draft assets to delete and the file names to upload.

    An interrupted upload can leave a draft with some assets, a partial one
    or none. Complete assets must have the expected digest. Only incomplete
    starter assets are deleted for retry; unrelated names or complete assets
    with a different digest are refused rather than touched.
    """
    stale, kept, seen = [], set(), set()
    for item in existing:
        release_assets.require(item['name'] in expected,
                               f"Unexpected asset {item['name']} on the draft")
        release_assets.require(item['name'] not in seen, 'Existing draft assets have duplicate names')
        seen.add(item['name'])
        if item.get('state') == 'uploaded':
            release_assets.require(item.get('digest') == expected[item['name']],
                                   'Existing draft assets differ from the verified files')
            kept.add(item['name'])
        else:
            release_assets.require(item.get('state') == 'starter',
                                   'Existing draft asset has an unknown upload state')
            stale.append(item['id'])
    return stale, sorted(set(expected) - kept)


def main():
    repo = os.environ['REPO']
    tag = os.environ['TAG']
    ci_run = os.environ['CI_RUN']
    release_run = os.environ['RELEASE_RUN']
    match = re.fullmatch(r'v([0-9]+\.[0-9]+\.[0-9]+)', tag)
    release_assets.require(match is not None, 'TAG must look like v4.2.0')
    release_assets.require(re.fullmatch(r'[0-9]+', ci_run) and re.fullmatch(r'[0-9]+', release_run),
                           'Run IDs must be numeric')
    version = match.group(1)

    source = checked_run(repo, release_run, 'Release', RELEASE_WORKFLOW)
    release_assets.require(checked_run(repo, ci_run, 'CI', CI_WORKFLOW) == source,
                           'CI and Release built different commits')

    work = Path(tempfile.mkdtemp(prefix='nekokem-publish-', dir=os.environ.get('RUNNER_TEMP')))
    artifacts = work / 'artifacts'
    gh('run', 'download', release_run, '--repo', repo, '--dir', str(artifacts))
    version_code = release_assets.verify(artifacts, version, source, release_run)
    public = work / 'public'
    hashes = release_assets.collect(artifacts, public)
    assets = sorted(public.iterdir())
    release_assets.require(len(assets) == len(release_assets.PACKAGES) + 1,
                           'Unexpected public asset set')
    for asset in assets:
        check_attestation(repo, asset, source, release_run)

    notes_source = gh('api', '-H', 'Accept: application/vnd.github.raw',
                      f'repos/{repo}/contents/release/{tag}.md?ref={source}')
    notes = release_assets.fill_notes(notes_source, source, ci_run, release_run, hashes)
    notes_path = work / 'notes.md'
    notes_path.write_text(notes, encoding='utf-8')

    tagged = peel_tag(repo, tag)
    release_assets.require(tagged in (None, source), f'Tag {tag} points to another commit')
    releases = list_releases(repo)
    matches = [release for release in releases
               if release['tag_name'] == tag]
    release_assets.require(len(matches) <= 1, f'Several releases use {tag}')
    if matches:
        release = matches[0]
        release_assets.require(release['draft'], f'{tag} is already published; refusing to change it')
        release_assets.require(release['target_commitish'] == source and release['body'] == notes,
                               f'The existing {tag} draft differs from this publication')
    else:
        # The release list can lag behind a new draft, so keep the ID the create call returns.
        try:
            created = json.loads(gh('api', '-X', 'POST', f'repos/{repo}/releases',
                                    '-f', f'tag_name={tag}', '-f', f'target_commitish={source}',
                                    '-f', f'name=NekoKEM {tag}', '-F', f'body=@{notes_path}',
                                    '-F', 'draft=true'))
        except Exception:
            print(f'Draft creation for {tag} did not return a release ID; '
                  'creation state is unconfirmed. Inspect repository drafts before retrying.',
                  file=sys.stderr)
            raise
        try:
            release = api(f"repos/{repo}/releases/{created['id']}")
            release_assets.require(release['draft'] and release['tag_name'] == tag and
                                   release['target_commitish'] == source,
                                   'GitHub did not create the expected draft')
        except Exception:
            restore_draft(repo, created['id'])
            raise
    release_id = release['id']
    expected = {asset.name: 'sha256:' + release_assets.sha256(asset) for asset in assets}
    endpoint = f'repos/{repo}/releases/{release_id}'
    try:
        stale, missing = plan_upload(release['assets'], expected)
        for asset_id in stale:
            gh('api', '-X', 'DELETE', f'repos/{repo}/releases/assets/{asset_id}')
        for name in missing:
            gh('api', '-X', 'POST',
               f'https://uploads.github.com/repos/{repo}/releases/{release_id}/assets'
               f'?name={quote(name, safe="")}',
               '-H', 'Content-Type: application/octet-stream', '--input', str(public / name))
        release = api(endpoint)
        release_assets.require({item['name']: item['digest'] for item in release['assets']} == expected,
                               'Uploaded assets differ from the verified files')
        release_assets.require(release['body'] == notes, 'Draft notes differ from the verified notes')

        # Explicit false preserves the current Latest when publishing an older version.
        latest = 'true' if is_highest_version(tag, list_releases(repo)) else 'false'
        gh('api', '-X', 'PATCH', endpoint, '-F', 'draft=false', '-F', 'prerelease=false',
           '-f', f'make_latest={latest}')
        release = api(endpoint)
        release_assets.require(not release['draft'] and not release['prerelease'],
                               f'{tag} was not published as a full release')
        release_assets.require({item['name']: item['digest'] for item in release['assets']} == expected,
                               'Published assets differ from the verified files')
        release_assets.require(peel_tag(repo, tag) == source, f'Published {tag} points to another commit')
    except Exception:
        restore_draft(repo, release_id)
        raise

    summary = (f"Published [{tag}]({release['html_url']}) from `{source}`: "
               f"{len(assets)} attested assets, Android versionCode {version_code}.\n")
    print(summary, end='')
    if os.environ.get('GITHUB_STEP_SUMMARY'):
        with open(os.environ['GITHUB_STEP_SUMMARY'], 'a', encoding='utf-8') as stream:
            stream.write(summary)


if __name__ == '__main__':
    try:
        main()
    except release_assets.ReleaseError as error:
        raise SystemExit(f'Publication refused: {error}')
    except subprocess.CalledProcessError as error:
        raise SystemExit(f'Publication failed: {error.cmd[:3]} exited {error.returncode}\n{error.stderr}')

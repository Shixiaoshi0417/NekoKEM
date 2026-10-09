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

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release_assets  # noqa: E402

RELEASE_WORKFLOW = '.github/workflows/release.yml'
CI_WORKFLOW = '.github/workflows/ci.yml'


def gh(*args, **kwargs):
    return subprocess.run(['gh', *args], check=True, capture_output=True, text=True, **kwargs).stdout


def api(path):
    return json.loads(gh('api', path))


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
    or none. A complete asset with the expected digest is kept; a partial or
    different one is replaced. A name this publication does not own is
    refused rather than touched.
    """
    stale, kept = [], set()
    for item in existing:
        release_assets.require(item['name'] in expected,
                               f"Unexpected asset {item['name']} on the draft")
        if item.get('state') == 'uploaded' and item.get('digest') == expected[item['name']]:
            kept.add(item['name'])
        else:
            stale.append(item['id'])
    return stale, sorted(set(expected) - kept)


def main():
    repo = os.environ['REPO']
    tag = os.environ['TAG']
    ci_run = os.environ['CI_RUN']
    release_run = os.environ['RELEASE_RUN']
    match = re.fullmatch(r'v(\d+\.\d+\.\d+)', tag)
    release_assets.require(match is not None, 'TAG must look like v4.2.0')
    release_assets.require(re.fullmatch(r'\d+', ci_run) and re.fullmatch(r'\d+', release_run),
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
    matches = [release for release in api(f'repos/{repo}/releases?per_page=100')
               if release['tag_name'] == tag]
    release_assets.require(len(matches) <= 1, f'Several releases use {tag}')
    if matches:
        release = matches[0]
        release_assets.require(release['draft'], f'{tag} is already published; refusing to change it')
        release_assets.require(release['target_commitish'] == source and release['body'] == notes,
                               f'The existing {tag} draft differs from this publication')
    else:
        # The release list can lag behind a new draft, so keep the ID the create call returns.
        created = json.loads(gh('api', '-X', 'POST', f'repos/{repo}/releases',
                                '-f', f'tag_name={tag}', '-f', f'target_commitish={source}',
                                '-f', f'name=NekoKEM {tag}', '-F', f'body=@{notes_path}',
                                '-F', 'draft=true'))
        release = api(f"repos/{repo}/releases/{created['id']}")
        release_assets.require(release['draft'] and release['tag_name'] == tag,
                               'GitHub did not create the expected draft')
    release_id = release['id']
    expected = {asset.name: 'sha256:' + release_assets.sha256(asset) for asset in assets}
    stale, missing = plan_upload(release['assets'], expected)
    for asset_id in stale:
        gh('api', '-X', 'DELETE', f'repos/{repo}/releases/assets/{asset_id}')
    if missing:
        gh('release', 'upload', tag, '--repo', repo, *[str(public / name) for name in missing])
    release = api(f'repos/{repo}/releases/{release_id}')
    release_assets.require({item['name']: item['digest'] for item in release['assets']} == expected,
                           'Uploaded assets differ from the verified files')
    release_assets.require(release['body'] == notes, 'Draft notes differ from the verified notes')

    gh('release', 'edit', tag, '--repo', repo, '--draft=false', '--latest')
    release = api(f'repos/{repo}/releases/{release_id}')
    release_assets.require(not release['draft'] and not release['prerelease'],
                           f'{tag} was not published as a full release')
    release_assets.require({item['name']: item['digest'] for item in release['assets']} == expected,
                           'Published assets differ from the verified files')
    release_assets.require(peel_tag(repo, tag) == source, f'Published {tag} points to another commit')

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

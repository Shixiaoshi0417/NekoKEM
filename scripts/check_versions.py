#!/usr/bin/env python3
"""Fail when application, package or Android signing versions drift apart."""
import argparse
import ast
import json
from pathlib import Path
import re
import tomllib


def windows_cli_version_is_derived(text):
    """Check metadata provenance without executing the packaging script."""
    embedded = re.search(r"<<'PYMETA'\n(.*?)^PYMETA$", text, re.M | re.S)
    if embedded is None:
        return False
    try:
        tree = ast.parse(embedded.group(1))
    except SyntaxError:
        return False
    values = {}
    for statement in tree.body:
        if isinstance(statement, ast.Assign):
            for target in statement.targets:
                if isinstance(target, ast.Name):
                    values.setdefault(target.id, []).append(statement.value)
    if any(len(values.get(name, [])) != 1 for name in ('reported', 'version', 'metadata')):
        return False
    record = values['metadata'][0]
    if not isinstance(record, ast.Dict):
        return False
    versions = [value for key, value in zip(record.keys, record.values)
                if isinstance(key, ast.Constant) and key.value == 'version']
    expected = ast.parse('version.group(1)', mode='eval').body
    if len(versions) != 1 or ast.dump(versions[0]) != ast.dump(expected):
        return False
    expected = ast.parse("re.fullmatch(r'NekoKEM ([0-9]+\\.[0-9]+\\.[0-9]+)',reported)",
                         mode='eval').body
    if ast.dump(values['version'][0]) != ast.dump(expected):
        return False
    reported = values['reported'][0]
    calls = [node for node in ast.walk(reported) if isinstance(node, ast.Call) and
             isinstance(node.func, ast.Attribute) and isinstance(node.func.value, ast.Name) and
             node.func.value.id == 'subprocess' and node.func.attr == 'run']
    if len(calls) != 1 or len(calls[0].args) != 1:
        return False
    expected = ast.parse("[str(root/'nekokem.exe'),'--version']", mode='eval').body
    keywords = {item.arg: item.value for item in calls[0].keywords}
    return ast.dump(calls[0].args[0]) == ast.dump(expected) and all(
        isinstance(keywords.get(name), ast.Constant) and keywords[name].value is True
        for name in ('check', 'capture_output', 'text'))


def check_versions(root):
    root = Path(root)
    version = json.loads((root / 'desktop/package.json').read_text())['version']
    if re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version) is None:
        raise ValueError('desktop/package.json has an invalid application version')
    errors = []

    def same(label, actual, expected=version):
        if actual != expected:
            errors.append(f'{label}: {actual!r}, expected {expected!r}')

    def matches(relative, pattern, expected=version):
        found = re.findall(pattern, (root / relative).read_text())
        if not found:
            errors.append(f'{relative}: required version declaration is missing')
        for actual in found:
            same(relative, actual, expected)

    lock = json.loads((root / 'desktop/package-lock.json').read_text())
    same('desktop/package-lock.json root', lock['version'])
    same('desktop/package-lock.json application package', lock['packages']['']['version'])
    same('desktop/src-tauri/tauri.conf.json',
         json.loads((root / 'desktop/src-tauri/tauri.conf.json').read_text())['version'])
    same('desktop/src-tauri/Cargo.toml',
         tomllib.loads((root / 'desktop/src-tauri/Cargo.toml').read_text())['package']['version'])
    packages = tomllib.loads((root / 'desktop/src-tauri/Cargo.lock').read_text())['package']
    application = [package for package in packages if package['name'] == 'nekokem-gui']
    if len(application) != 1:
        errors.append('desktop/src-tauri/Cargo.lock: expected one application package')
    else:
        same('desktop/src-tauri/Cargo.lock application', application[0]['version'])

    matches('linux/src/main.c', r'#define NEKOKEM_CLI_VERSION "([^"]+)"')
    matches('linux/packaging/README', r'^NekoKEM Linux CLI ([^\n]+)')
    matches('linux/install.sh', r'(?m)^release_tag="v([^"]+)"')
    matches('linux/scripts/build-linux-release.sh', r'NekoKEM ([0-9]+\.[0-9]+\.[0-9]+)')
    matches('macos/scripts/build-macos-cli.sh', r'NekoKEM ([0-9]+\.[0-9]+\.[0-9]+)')
    matches('macos/scripts/build-macos-cli.sh', r"'version':\s*'([^']+)'")
    windows_script = 'windows/scripts/build-windows-cli.sh'
    if not windows_cli_version_is_derived((root / windows_script).read_text()):
        errors.append(f'{windows_script}: version metadata must use a checked executable --version response')
    matches('desktop/scripts/package-linux-gui.py', r"'neko-kem',\s*'([^']+)'")
    matches('desktop/scripts/package-linux-gui.py', r"field\('Version'\) == '([^']+)'")
    for workflow in ('ci.yml', 'release.yml'):
        matches(f'.github/workflows/{workflow}', r'--version ([0-9]+\.[0-9]+\.[0-9]+)')
        relative = f'.github/workflows/{workflow}'
        source = (root / relative).read_text()
        if ('$version = (Get-Content desktop/src-tauri/tauri.conf.json -Raw | ConvertFrom-Json).version'
                not in source or 'version=$version; exe_sha256' not in source):
            errors.append(f'{relative}: Windows GUI metadata must use the checked Tauri configuration version')

    matches('android/app/build.gradle.kts', r'versionName\s*=\s*"([^"]+)"')
    matches('.github/scripts/verify_android_release.py', r"(?m)^VERSION = '([^']+)'")
    gradle = (root / 'android/app/build.gradle.kts').read_text()
    code = re.findall(r'versionCode\s*=\s*([0-9]+)', gradle)
    if len(code) != 1:
        errors.append('android/app/build.gradle.kts: expected one versionCode')
    else:
        matches('.github/scripts/verify_android_release.py', r'(?m)^VERSION_CODE = ([0-9]+)', code[0])
    for directory in ('values', 'values-zh-rCN', 'values-zh-rTW', 'values-ja', 'values-ko'):
        matches(f'android/app/src/main/res/{directory}/strings.xml',
                r'<string name="app_version">NekoKEM v([^<]+)</string>')
    return version, errors


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    try:
        version, errors = check_versions(args.root)
    except (OSError, KeyError, ValueError) as error:
        raise SystemExit(f'Version consistency check failed: {error}')
    if errors:
        raise SystemExit('Version consistency check failed:\n' + '\n'.join(errors))
    print(f'Application and signing versions are consistent: {version}')


if __name__ == '__main__':
    main()

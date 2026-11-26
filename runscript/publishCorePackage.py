#!/usr/bin/env python3
"""Publish an assembled local Core release using the same OCI format as CI."""
import argparse
import io
import json
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.error
import zipfile
from pathlib import Path

import core_package as package

ROOT = Path(__file__).resolve().parents[1]
MOBILE = {package.ASSETS[key] for key in ('android', 'ios', 'harmony')}
FILES = [*package.ASSETS.values(), 'manifest.json', 'checksums-sha256.txt', 'apple-dsym-uuids.txt']


def inputs(directory):
    for name in FILES:
        path = directory / name
        if path.is_symlink() or not path.is_file() or not path.stat().st_size:
            raise ValueError(f'Missing or invalid release file: {name}')
    release = json.loads((directory / 'manifest.json').read_bytes())
    version = release['version'].removeprefix('v')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?', version):
        raise ValueError('Invalid release version in manifest.json')
    core = release['sourceRevision']
    if release['sourceDirty'] is not False or not re.fullmatch('[a-f0-9]{40}', core):
        raise ValueError('Expected a clean Core build with a full source revision')
    hashes = {name: package.digest(directory / name) for name in FILES}
    recorded = {a['file']: 'sha256:' + a['sha256'] for a in release['artifacts']}
    checksums = {}
    for line in (directory / 'checksums-sha256.txt').read_text().splitlines():
        sha, name = line.split()
        checksums[name] = 'sha256:' + sha
    for expected, names in [(recorded, MOBILE), (checksums, MOBILE | {'apple-dsym-uuids.txt'})]:
        if set(expected) != names or any(hashes[name] != sha for name, sha in expected.items()):
            raise ValueError('Mobile release metadata does not match the local files')
    with zipfile.ZipFile(directory / package.ASSETS['macos']) as framework:
        framework.getinfo('Shared.framework/Shared')
        if framework.testzip() is not None:
            raise ValueError('Damaged macOS framework archive')
    pins = {'core': core}
    for name in ('cli', 'lifi'):
        pins[name] = subprocess.check_output(['git', '-C', ROOT, 'show', f'{core}:{name}.revision'], text=True).strip()
    with tarfile.open(directory / package.ASSETS['cli-macos']) as archive:
        entries = {m.name.removeprefix('./'): m for m in archive if m.isfile()}
        if 'deadliner' not in entries:
            raise ValueError('Mac CLI archive is missing its executable')
        for name, revision in pins.items():
            if archive.extractfile(entries[f'{name}.revision']).read().decode().strip() != revision:
                raise ValueError(f'Mac CLI was built with a different {name} revision')
    with tarfile.open(directory / package.ASSETS['cli-linux']) as archive:
        linux_jar = archive.extractfile('deadliner/lib/app/deadliner-cli.jar').read()
    with zipfile.ZipFile(directory / package.ASSETS['cli-windows']) as archive:
        windows_jar = archive.read('deadliner/app/deadliner-cli.jar')
    if linux_jar != windows_jar:
        raise ValueError('Windows and Linux CLI archives contain different Core JARs')
    with zipfile.ZipFile(io.BytesIO(linux_jar)) as jar:
        metadata = jar.read('com/deadliner/platform/KmpBuildMetadata.class')
        if f'DeadlinerCore-{core[:6]}-'.encode() not in metadata:
            raise ValueError('Desktop CLI archives were built from a different Core revision')
    annotations = {'org.opencontainers.image.revision': core, 'org.opencontainers.image.version': version,
                   'org.opencontainers.image.created': release['builtAt'],
                   'com.deadlinerstudio.cli.revision': pins['cli'], 'com.deadlinerstudio.lifi.revision': pins['lifi']}
    template = directory / 'windows-template.json'
    if template.exists():
        info = json.loads(template.read_bytes())
        if info['windows'] != hashes[package.ASSETS['cli-windows']] or info['packager'] != pins['cli']:
            raise ValueError('Windows template record does not match this release')
        for field in ('templateManifest', 'templateWindows'):
            if not re.fullmatch(r'sha256:[0-9a-f]{64}', info[field]):
                raise ValueError(f'Invalid Windows template digest: {field}')
        annotations.update({'com.deadlinerstudio.packager.revision': info['packager'],
                            'com.deadlinerstudio.windows.template.manifest': info['templateManifest'],
                            'com.deadlinerstudio.windows.template.layer': info['templateWindows']})
    return version, annotations, hashes


def remote_version(version):
    try:
        with package.read_registry('manifests', version) as response:
            return json.load(response), response.headers['Docker-Content-Digest']
    except urllib.error.HTTPError as error:
        if error.code != 404:
            raise
        return None, None


def matches(manifest, annotations, hashes):
    layers = manifest['layers']
    remote = {layer['annotations']['org.opencontainers.image.title']: layer['digest'] for layer in layers}
    return (len(layers) == len(hashes) and remote == hashes and
            manifest.get('artifactType') == 'application/vnd.deadlinerstudio.core.v1' and
            all(manifest['annotations'].get(k) == v for k, v in annotations.items() if k != 'org.opencontainers.image.created'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path, help='directory containing the seven platform files and mobile release metadata')
    parser.add_argument('--check', action='store_true', help='build and verify a temporary OCI layout entirely offline')
    args = parser.parse_args()
    directory = args.directory.resolve()
    version, annotations, hashes = inputs(directory)
    destination = f'ghcr.io/{package.REPOSITORY}:{version}'
    oras = shutil.which('oras') or ROOT / 'build/tools/oras/oras'
    if not Path(oras).is_file():
        raise ValueError('ORAS is required. Install it with brew install oras on macOS.')
    cmd = [oras, 'push', '--artifact-type', 'application/vnd.deadlinerstudio.core.v1']
    for key, value in annotations.items():
        cmd.extend(['--annotation', f'{key}={value}'])
    if args.check:
        with tempfile.TemporaryDirectory() as tmp:
            manifest = Path(tmp) / 'manifest.json'
            subprocess.run([*cmd, '--export-manifest', manifest, '--oci-layout', f'{tmp}/layout:{version}', *FILES], cwd=directory, check=True)
            if not matches(json.loads(manifest.read_bytes()), annotations, hashes):
                raise ValueError('Local OCI manifest verification failed')
        return
    manifest, digest = remote_version(version)
    if manifest is None:
        user, token = package.github_credentials()
        subprocess.run([*cmd, '--username', user, '--password-stdin', destination, *FILES],
                       cwd=directory, input=token + '\n', text=True, check=True)
        manifest, digest = remote_version(version)
    if manifest is None or not matches(manifest, annotations, hashes):
        raise ValueError(f'{destination} does not match this release. Use a new version for different files.')
    print(json.dumps({'version': version, 'manifest': digest}, indent=2))


if __name__ == '__main__':
    try:
        main()
    except (OSError, KeyError, ValueError, tarfile.TarError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        sys.exit(f'Core package publication failed: {error}')

#!/usr/bin/env python3
"""Combine a pinned Windows launcher/runtime with the Mac build's complete JAR set."""
import argparse
import json
import os
import struct
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

import core_package as package

ROOT = Path(__file__).resolve().parents[1]
WINDOWS = package.ASSETS['cli-windows']


def verify_pe(binary):
    offset = int.from_bytes(binary[60:64], 'little')
    if binary[:2] != b'MZ' or binary[offset:offset+6] != b'PE\0\0' + struct.pack('<H', 0x8664):
        raise ValueError('Expected a Windows x64 PE executable')


def repack(bundle, baseline):
    core = (bundle / 'core.revision').read_text().strip()
    packager = subprocess.check_output(['git', '-C', ROOT, 'show', f'{core}:cli.revision'], text=True).strip()
    cache = ROOT / 'build' / 'windows-template-cache'
    cache.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(package.fetch('manifests', baseline, cache).read_bytes())
    if manifest['annotations'].get('com.deadlinerstudio.packager.revision') != packager:
        raise ValueError('The Windows template and local build use different packager revisions')
    layer, = (item for item in manifest['layers'] if item['annotations'].get('org.opencontainers.image.title') == WINDOWS)
    template = package.fetch('blobs', layer['digest'], cache)
    jars = sorted((bundle / 'jars').iterdir())
    if not any(p.name == 'deadliner-cli.jar' for p in jars) or any(not p.is_file() or p.suffix != '.jar' for p in jars):
        raise ValueError('Expected a staged desktop JAR directory')
    destination = bundle / 'release'
    with tempfile.TemporaryDirectory(dir=destination) as tmp:
        staged = Path(tmp) / WINDOWS
        with zipfile.ZipFile(template) as src, zipfile.ZipFile(staged, 'w', compression=zipfile.ZIP_DEFLATED) as dst:
            for name in ('deadliner/deadliner.exe', 'deadliner/runtime/bin/java.exe', 'deadliner/runtime/bin/server/jvm.dll'):
                verify_pe(src.read(name))
            for entry in src.infolist():
                name = entry.filename
                if name.startswith('deadliner/runtime/') or name in ('deadliner/deadliner.exe', 'deadliner/CLI-LICENSE'):
                    dst.writestr(entry, src.read(entry))
            for jar in jars:
                dst.write(jar, 'deadliner/app/' + jar.name)
        with zipfile.ZipFile(staged) as rebuilt, zipfile.ZipFile(template) as src:
            for name in rebuilt.namelist():
                if name.startswith('deadliner/app/'):
                    expected = (bundle / 'jars' / Path(name).name).read_bytes()
                else:
                    expected = src.read(name)
                if rebuilt.read(name) != expected:
                    raise ValueError(f'Repacked content differs: {name}')
        info = {'templateManifest': baseline, 'templateWindows': layer['digest'], 'packager': packager,
                'windows': package.digest(staged)}
        record = Path(tmp) / 'windows-template.json'
        record.write_text(json.dumps(info, indent=2)+'\n')
        os.replace(staged, destination / WINDOWS)
        os.replace(record, destination / record.name)
        print(json.dumps(info, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('bundle', type=Path)
    parser.add_argument('manifest_digest', help='immutable OCI manifest digest of a tested Windows template')
    args = parser.parse_args()
    try:
        repack(args.bundle.resolve(), args.manifest_digest)
    except (OSError, KeyError, ValueError, zipfile.BadZipFile, subprocess.CalledProcessError) as error:
        sys.exit(f'Windows packaging failed: {error}')

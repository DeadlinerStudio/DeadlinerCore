#!/usr/bin/env python3
"""Run inside the Mac-produced bundle on x64 Linux or Windows with JDK 21."""
import json
import os
import platform
import shutil
import subprocess
import sys
import tarfile
import tempfile
from pathlib import Path


def main():
    root = Path(__file__).resolve().parent
    host = platform.system()
    if host not in ('Linux', 'Windows') or platform.machine().lower() not in ('amd64', 'x86_64'):
        raise SystemExit('Requires x64 Linux or Windows.')
    target = host.lower() + '-x64'
    core = (root / 'core.revision').read_text().strip()
    packager = root / 'packager'
    with tempfile.TemporaryDirectory(dir=root) as tmp:
        dist = Path(tmp) / 'dist'
        subprocess.run([sys.executable, packager/'scripts/package-desktop.py', root/'jars', dist], check=True)
        binary = dist / 'deadliner' / ('deadliner.exe' if host == 'Windows' else 'bin/deadliner')
        version = json.loads(subprocess.check_output([binary, '--json', 'version']))['data']
        schema = json.loads(subprocess.check_output([binary, '--json', 'schema']))['data']
        if schema['platform'] != target or not version['lifi'] or core[:6] not in version['core']:
            raise SystemExit('Packaged executable does not match the Core revision and platform.')
        subprocess.run([sys.executable, packager/'tests/integration.py'], check=True,
                       env={**os.environ, 'DEADLINER_TEST_BINARY': str(binary)})
        if host == 'Windows':
            archive = Path(shutil.make_archive(str(Path(tmp)/'deadliner-windows-x64'), 'zip', dist, 'deadliner'))
        else:
            archive = Path(tmp)/'deadliner-linux-x64.tar.gz'
            with tarfile.open(archive, 'w:gz') as out:
                out.add(dist/'deadliner', arcname='deadliner')
        final = root / archive.name
        os.replace(archive, final)
        print(final)


if __name__ == '__main__':
    main()

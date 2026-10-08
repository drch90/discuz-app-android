#!/usr/bin/env python3
"""Check the public Android repository's tracked file boundary before CI/builds."""
from pathlib import Path
import re
import subprocess

root = Path(__file__).resolve().parents[1]
paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
root_files = {'.gitignore', 'README.md', 'build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
              'gradlew', 'gradlew.bat', 'site.properties.example', 'production.properties.example',
              'signing.properties.example'}
allowed_properties = {'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties'}
blocked_suffixes = {'.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.password', '.apk', '.aab'}
secret = re.compile(rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,})')
errors = []
for name in filter(None, paths):
    path = root / name
    if name not in root_files and not name.startswith(('app/', 'gradle/', 'tools/', '.github/workflows/')):
        errors.append(name + ': outside Android source boundary')
    if path.is_symlink() or not path.is_file():
        errors.append(name + ': not a regular source file')
        continue
    if path.suffix in blocked_suffixes or (path.suffix == '.properties' and name not in allowed_properties):
        errors.append(name + ': real configuration, signing file or binary output')
    if any(part in {'build', '.gradle', '.kotlin', '.secrets', 'node_modules'} for part in Path(name).parts):
        errors.append(name + ': cache/private directory')
    if path.name.startswith('.env') or secret.search(path.read_bytes()):
        errors.append(name + ': environment file or credential pattern')
if errors:
    raise SystemExit('\n'.join(errors))
print('PASS: tracked files stay inside the public Android boundary; configuration is example-only')

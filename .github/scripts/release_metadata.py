"""Validate a release tag before downloading the SDK or publishing assets."""
import os
from pathlib import Path
import re
import xml.etree.ElementTree as ET


def validate_release(tag, plugin_xml, gradle_build):
    version = ET.fromstring(plugin_xml).findtext('version')
    gradle_version = re.search(r'^version\s*=\s*"([^"]+)"\s*$', gradle_build, re.MULTILINE)
    if not version or not re.fullmatch(r'\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?', version):
        raise ValueError(f'Expected a release version like 0.4.1 or 0.4.2-rc.1, got {version!r}')
    if not gradle_version or gradle_version.group(1) != version:
        raise ValueError('build.gradle.kts version must match plugin.xml version')
    if tag != f'v{version}':
        raise ValueError(f'Tag {tag!r} must match plugin.xml version: v{version}')
    return version, '-' in version


def main():
    root = Path(__file__).resolve().parents[2]
    version, prerelease = validate_release(
        os.environ['RELEASE_TAG'],
        (root / 'src/main/resources/META-INF/plugin.xml').read_text(),
        (root / 'build.gradle.kts').read_text(),
    )
    with open(os.environ['GITHUB_OUTPUT'], 'a', encoding='utf-8') as output:
        output.write(f'version={version}\nprerelease={str(prerelease).lower()}\n')
    print(f'Validated release v{version} (prerelease={prerelease})')


if __name__ == '__main__':
    main()

#!/usr/bin/env python3
"""从插件安装包生成 GitHub Release 使用的 IDEA 自定义仓库索引。"""
import argparse
from copy import deepcopy
from io import BytesIO
from pathlib import Path
import re
from urllib.parse import quote
import xml.etree.ElementTree as ET
import zipfile


def read_descriptor(package):
    """读取分发 ZIP 内 JAR 中的描述文件，确保索引对应实际发布产物。"""
    descriptors = []
    with zipfile.ZipFile(package) as distribution:
        for name in distribution.namelist():
            if name.endswith('.jar'):
                with zipfile.ZipFile(BytesIO(distribution.read(name))) as jar:
                    if 'META-INF/plugin.xml' in jar.namelist():
                        descriptors.append(ET.fromstring(jar.read('META-INF/plugin.xml')))
    if len(descriptors) != 1:
        raise ValueError('安装包必须包含唯一的 META-INF/plugin.xml')
    return descriptors[0]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path, help='buildPlugin 或 build.py 生成的插件 ZIP')
    parser.add_argument('--repository', default='mcmy/flutter-debug-mcp', help='GitHub owner/repo')
    parser.add_argument('--tag', help='Release tag，默认 v<安装包内的版本>')
    parser.add_argument('--output', type=Path, default=Path('dist/updatePlugins.xml'))
    args = parser.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', args.repository):
        parser.error('--repository 应为 owner/repo')
    try:
        descriptor = read_descriptor(args.package)
        plugin_id = descriptor.findtext('id')
        version = descriptor.findtext('version')
        compatibility = descriptor.find('idea-version')
        if not plugin_id or not version or compatibility is None or not compatibility.get('since-build'):
            raise ValueError('安装包缺少 id、version 或 idea-version/since-build')
        tag = args.tag or f'v{version}'
        download = (f'https://github.com/{args.repository}/releases/download/'
                    f'{quote(tag, safe="")}/{quote(args.package.name, safe="")}')
        root = ET.Element('plugins')
        plugin = ET.SubElement(root, 'plugin', id=plugin_id, version=version, url=download)
        plugin.append(deepcopy(compatibility))
        for child in descriptor:
            if child.tag in ('name', 'description', 'vendor', 'change-notes'):
                plugin.append(deepcopy(child))
            elif child.tag == 'depends' and child.get('optional') != 'true':
                dependency = (child.text or '').strip()
                if dependency and not dependency.startswith('com.intellij.modules.'):
                    ET.SubElement(plugin, 'depends').text = dependency
        ET.indent(root, space='  ')
        args.output.parent.mkdir(parents=True, exist_ok=True)
        ET.ElementTree(root).write(args.output, encoding='utf-8', xml_declaration=True)
    except (OSError, ValueError, zipfile.BadZipFile, ET.ParseError) as error:
        parser.error(str(error))
    print(f'Generated: {args.output}')
    print(f'Release tag: {tag}')
    print(f'Upload both: {args.package} and {args.output}')
    print(f'IDEA repository URL: https://github.com/{args.repository}/releases/latest/download/updatePlugins.xml')


if __name__ == '__main__':
    main()

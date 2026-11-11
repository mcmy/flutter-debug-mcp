#!/usr/bin/env python3
"""使用2026.1平台SDK编译Java21插件，并运行关键回归。"""
import argparse
import os
from pathlib import Path
import shutil
import subprocess
import xml.etree.ElementTree as ET
import zipfile


def main():
    """编译源码、运行核心回归与VM协议测试并生成插件包。"""
    parser = argparse.ArgumentParser()
    parser.add_argument('--idea-home', default=str(Path.home() / 'Library/Caches/idea-flutter-mcp-sdk/ideaIC-2026.1'))
    parser.add_argument('--dart-jar', default=str(Path.home() / 'Library/Application Support/JetBrains/IntelliJIdea2026.3/plugins/Dart/lib/Dart-511.0.0.jar'))
    parser.add_argument('--java-home', default=os.environ.get('JAVA_HOME', str(Path.home() / 'Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home')))
    parser.add_argument('--flutter-jar', default=str(Path.home() / 'Library/Application Support/JetBrains/IntelliJIdea2026.3/plugins/flutter-intellij/lib/flutter-intellij-98.0.0.jar'))
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    classes = root / 'build/classes'
    if classes.exists():
        shutil.rmtree(classes)
    classes.mkdir(parents=True)
    cp = os.pathsep.join([str(Path(args.idea_home) / 'lib/*'), args.dart_jar, str(Path(args.flutter_jar))])
    javac = str(Path(args.java_home) / 'bin/javac')
    java = str(Path(args.java_home) / 'bin/java')
    if not (Path(args.idea_home) / 'lib').is_dir():
        parser.error('Platform SDK missing; set --idea-home to an extracted 2026.1 SDK directory containing lib/')
    sources = sorted((root / 'src/main/java').rglob('*.java'))
    subprocess.run([javac, '--release', '21', '-encoding', 'UTF-8', '-classpath', cp, '-d', str(classes), *map(str, sources)], check=True)
    subprocess.run([javac, '--release', '21', '-encoding', 'UTF-8', '-classpath', str(classes) + os.pathsep + cp, '-d', str(classes), *map(str, (root / 'src/test/java').rglob('*.java'))], check=True)
    shutil.copytree(root / 'src/main/resources', classes, dirs_exist_ok=True)
    for test in ('CoreTest', 'VmConnectionTest', 'McpHttpServerTest'):
        subprocess.run([java, '-ea', '-classpath', str(classes) + os.pathsep + cp, 'dev.mcmy.ideafluttermcp.' + test], check=True)
    dist = root / 'dist'
    dist.mkdir(exist_ok=True)
    jar = root / 'build/flutter-debug-mcp.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for source in classes.rglob('*.class'):
            if 'Test' not in source.name:
                archive.write(source, source.relative_to(classes).as_posix())
        for source in (root / 'src/main/resources').rglob('*'):
            if source.is_file():
                archive.write(source, source.relative_to(root / 'src/main/resources').as_posix())
    version = ET.parse(root / 'src/main/resources/META-INF/plugin.xml').findtext('version')
    if not version:
        raise ValueError('plugin.xml 缺少 version')
    output = dist / f'flutter-debug-mcp-{version}.zip'
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        archive.write(jar, 'flutter-debug-mcp/lib/flutter-debug-mcp.jar')
    print('Built:', output)


if __name__ == '__main__':
    main()

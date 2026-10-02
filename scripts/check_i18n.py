#!/usr/bin/env python3
"""Check translation coverage, printf signatures, generated catalogs and doc links."""
import ast
from collections import Counter
import json
from pathlib import Path
import re
import subprocess
import sys
import unicodedata
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
FORMAT = re.compile(r'%(?:(\d+)\$)?([-+#0 ]*)(\d+|\*)?(?:\.(\d+|\*))?(hh|ll|[hljztL])?([diouxXeEfFgGaAcsp%])')
LITERAL = r'"(?:\\.|[^"\\])*"'
SEQUENCE = rf'{LITERAL}(?:\s*{LITERAL})*'

def signature(text):
    result = []
    sequential = 0
    position = 0
    for match in FORMAT.finditer(text):
        if '%' in text[position:match.start()]:
            raise ValueError(f'Invalid format string: {text!r}')
        position = match.end()
        index, flags, width, precision, length, kind = match.groups()
        if kind == '%':
            continue
        if index is None:
            sequential += 1
            index = str(sequential)
        # Width/precision stars consume additional arguments; none are currently used.
        result.append((int(index), length or '', kind, width == '*', precision == '*'))
    if '%' in text[position:]:
        raise ValueError(f'Invalid format string: {text!r}')
    return Counter(result)

def resource_entries(path):
    entries = {}
    for element in ET.parse(path).getroot():
        key = element.attrib['name']
        if key in entries:
            raise ValueError(f'Duplicate resource {path}: {key}')
        if element.tag == 'plurals':
            entries[key] = {i.attrib['quantity']: ''.join(i.itertext()) for i in element}
            if 'other' not in entries[key]:
                raise ValueError(f'{path}: {key} needs other quantity')
        else:
            entries[key] = {'string': ''.join(element.itertext())}
    return entries

def android():
    directory = ROOT / 'android/app/src/main/res'
    base = resource_entries(directory / 'values/strings.xml')
    for qualifier in ['values-zh-rCN','values-zh-rTW','values-ja','values-ko']:
        path = directory / qualifier / 'strings.xml'
        translated = resource_entries(path)
        if set(base) != set(translated):
            raise ValueError(f'{path}: missing {set(base)-set(translated)}, extra {set(translated)-set(base)}')
        for key, forms in translated.items():
            expected = signature(next(iter(base[key].values())))
            for quantity, text in forms.items():
                if not text or signature(text) != expected:
                    raise ValueError(f'{path}: inconsistent {key}/{quantity}')
            if ('string' in forms) != ('string' in base[key]):
                raise ValueError(f'{path}: string/plural mismatch: {key}')
    used = set()
    for path in (ROOT / 'android/app/src/main/java').rglob('*.kt'):
        source = path.read_text()
        used.update(re.findall(r'R\.(?:string|plurals)\.(\w+)', source))
        if re.search(r'\bText\s*\(\s*(?:text\s*=\s*)?"', source):
            raise ValueError(f'{path}: hard-coded Compose text')
    if used - set(base):
        raise ValueError(f'Unknown Android resources: {used-set(base)}')
    locales = ET.parse(directory / 'xml/locales_config.xml').getroot()
    declared = {e.attrib['{http://schemas.android.com/apk/res/android}name'] for e in locales}
    if declared != {'en','zh-CN','zh-TW','ja','ko'}:
        raise ValueError('Unexpected Framework language list')
    print(f'Android: five complete catalogs, {len(base)} resources, format signatures checked')

def decode(sequence):
    return ''.join(ast.literal_eval(s) for s in re.findall(LITERAL, sequence))

def cli():
    catalogs = [json.loads((ROOT/'linux/i18n'/f'{lang}.json').read_text())
                for lang in ['en','zh-CN','zh-TW','ja','ko']]
    base = catalogs[0]
    for catalog in catalogs:
        if set(base) != set(catalog):
            raise ValueError('CLI message coverage differs')
        for source, text in catalog.items():
            if not text or signature(source) != signature(text):
                raise ValueError(f'CLI placeholder mismatch: {source!r}')
    used = set()
    paths = (list((ROOT/'core/src').glob('*.c')) + list((ROOT/'core/src').glob('*.inc')) +
             list((ROOT/'linux/src').glob('*.c')) + list((ROOT/'windows/src').glob('*.c')))
    for path in paths:
        source = path.read_text()
        used.update(decode(m[1]) for m in re.finditer(
            rf'(?:file_message|print_system_error|print_openssl_error)\s*\(\s*({SEQUENCE})', source))
        for m in re.finditer(rf"(?:read_prompt_line|read_password_line)\s*\(\s*({SEQUENCE})", source):
            raise ValueError(f"{path}: untranslated interactive prompt {decode(m[1])!r}")
        # Every direct human-facing literal must opt into translation. Pure external
        # diagnostic formatting and --version are deliberate invariant exceptions.
        for m in re.finditer(rf'(?<![\w])(?:fprintf\s*\(\s*stderr\s*,\s*|printf\s*\(\s*|fputs\s*\(\s*)({SEQUENCE})', source):
            text = decode(m[1])
            if text not in {'%s\n','%s: %s\n','OpenSSL: %s\n'}:
                raise ValueError(f'{path}: untranslated output literal {text!r}')
    if used - set(base):
        raise ValueError(f'Uncatalogued CLI messages: {used-set(base)}')
    subprocess.run([sys.executable,str(ROOT/'scripts/generate_cli_messages.py'),'--check'],check=True)
    print(f'CLI: five complete catalogs, {len(base)} messages, format signatures checked')

def slug(title):
    title = re.sub(r'[`*_]', '', title).strip().lower()
    return ''.join(c for c in title if c in '- ' or unicodedata.category(c)[0] in 'LN').replace(' ','-')

def documents():
    for name in ['README','SECURITY']:
        texts = [(ROOT/f'{name}{suffix}.md').read_text() for suffix in ['', '.en']]
        navigation = f'[简体中文]({name}.md) | [English]({name}.en.md)'
        for suffix, text in zip(['','.en'],texts):
            if not text.startswith(navigation+'\n'):
                raise ValueError(f'{name}{suffix}: missing language navigation')
            stripped = re.sub(r'```.*?```','',text,flags=re.S)
            targets = re.findall(r'\]\(([^\s)]+)(?:\s+[^)]*)?\)',stripped)
            targets += re.findall(r'<img\b[^>]*\bsrc="([^"]+)"',stripped)
            for target in targets:
                if re.match(r'[a-zA-Z]+:',target): continue
                relative, _, fragment = target.partition('#')
                path = ROOT / (relative or f'{name}{suffix}.md')
                if not path.exists(): raise ValueError(f'Broken relative link: {target}')
                if fragment and path.suffix=='.md':
                    anchors={slug(h) for h in re.findall(r'^#+\s+(.+)$',path.read_text(),re.M)}
                    if fragment not in anchors: raise ValueError(f'Broken anchor: {target}')
        # Prevent section or reproducible-command omissions; translated text/menu
        # blocks are reviewed separately and must keep their section ordering.
        if len(re.findall(r'^#+ ',texts[0],re.M)) != len(re.findall(r'^#+ ',texts[1],re.M)):
            raise ValueError(f'{name}: unequal section coverage')
        blocks = [re.findall(r'```sh\n(.*?)```',text,re.S) for text in texts]
        if blocks[0] != blocks[1]: raise ValueError(f'{name}: command examples differ')
        addresses=[set(re.findall(r'mailto:([^\s)]+)',text)) for text in texts]
        if addresses[0] != addresses[1]: raise ValueError(f'{name}: reporting addresses differ')
    print('README/SECURITY: bilingual navigation, sections, commands, addresses and relative links checked')

if __name__=='__main__':
    android(); cli(); documents()

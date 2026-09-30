#!/usr/bin/env python3
"""Mutation regressions prove missing translations and unsafe formats fail CI."""
import contextlib
import io
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
import check_i18n

class CheckTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        for path in ['android/app/src/main/res','android/app/src/main/java','core/src',
                     'linux/src','linux/i18n','scripts','docs']:
            shutil.copytree(check_i18n.ROOT/path,self.root/path,
                            ignore=shutil.ignore_patterns('*.o','*.d','__pycache__'))
        for path in ['README.md','README.en.md','SECURITY.md','SECURITY.en.md','LICENSE','icon.png','android/README.md']:
            target=self.root/path; target.parent.mkdir(parents=True,exist_ok=True)
            shutil.copyfile(check_i18n.ROOT/path,target)
        self.patch=patch.object(check_i18n,'ROOT',self.root); self.patch.start()
        self.output=contextlib.redirect_stdout(io.StringIO()); self.output.__enter__()
    def tearDown(self):
        self.output.__exit__(None,None,None); self.patch.stop(); self.temp.cleanup()
    def test_missing_android_resource_is_rejected(self):
        path=self.root/'android/app/src/main/res/values-ko/strings.xml'
        path.write_text(path.read_text().replace('<string name="navigation_settings">설정</string>',''))
        with self.assertRaises(ValueError): check_i18n.android()
    def test_new_untranslated_android_resource_is_rejected(self):
        path=self.root/'android/app/src/main/res/values/strings.xml'
        path.write_text(path.read_text().replace('</resources>','<string name="new_warning">Warning</string></resources>'))
        with self.assertRaises(ValueError): check_i18n.android()
    def test_android_placeholder_mismatch_is_rejected(self):
        path=self.root/'android/app/src/main/res/values-ja/strings.xml'
        path.write_text(path.read_text().replace('%1$d','%1$s',1))
        with self.assertRaises(ValueError): check_i18n.android()
    def test_cli_missing_message_is_rejected(self):
        path=self.root/'linux/i18n/ko.json'; catalog=json.loads(path.read_text()); catalog.pop(next(iter(catalog)))
        path.write_text(json.dumps(catalog))
        with self.assertRaises(ValueError): check_i18n.cli()
    def test_cli_placeholder_mismatch_is_rejected(self):
        path=self.root/'linux/i18n/ja.json'; catalog=json.loads(path.read_text())
        key=next(k for k in catalog if '%u' in k); catalog[key]=catalog[key].replace('%u','%s')
        path.write_text(json.dumps(catalog))
        with self.assertRaises(ValueError): check_i18n.cli()
    def test_new_hardcoded_cli_output_is_rejected(self):
        path=self.root/'linux/src/main.c'; path.write_text(path.read_text()+'\nvoid regression(void) { fprintf(stderr, "New warning\\n"); }\n')
        with self.assertRaises(ValueError): check_i18n.cli()
    def test_broken_document_link_is_rejected(self):
        path=self.root/'README.en.md'; path.write_text(path.read_text().replace('](LICENSE)','](missing-license)'))
        with self.assertRaises(ValueError): check_i18n.documents()
    def test_removed_document_section_is_rejected(self):
        path=self.root/'README.en.md'; path.write_text(path.read_text().replace('## Security boundaries','Security boundaries'))
        with self.assertRaises(ValueError): check_i18n.documents()

if __name__=='__main__': unittest.main(verbosity=2)

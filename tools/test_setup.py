"""Offline setup regression checks; synthetic inputs only, no ADB/network."""
from pathlib import Path
import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from configure import mappings
from local_config import load


class SetupTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        self.root=Path(self.temp.name)
        self.config={'modern_serial':'AuditModern','s2_serial':'AuditS2',
                     'modern_bluetooth':':'.join(('02','11','22','33','44','55')),
                     's2_bluetooth':':'.join(('02','11','22','33','44','66')),
                     'wifi_ssid':'AuditNetwork', 'call_app_uid':None,
                     'app_signer_sha256':None,'identity_jar_sha256':None}
    def tearDown(self): self.temp.cleanup()
    def read(self):
        p=self.root/'local.json';p.write_text(json.dumps(self.config),encoding='utf-8')
        return load(p)
    def test_modern_config_maps_legacy_tokens(self):
        self.assertEqual(mappings(self.read())['@PIXEL_SERIAL@'],'AuditModern')
    def test_legacy_config_is_compatible(self):
        for suffix in ('serial','bluetooth'):
            self.config['pixel_'+suffix]=self.config.pop('modern_'+suffix)
        self.assertEqual(mappings(self.read())['@PIXEL_SERIAL@'],'AuditModern')
    def test_conflicting_aliases_stop(self):
        self.config['pixel_serial']='WrongDevice'
        with self.assertRaises(ValueError): self.read()
    def test_duplicate_serials_stop(self):
        self.config['modern_serial']=self.config['s2_serial']
        with self.assertRaises(ValueError): mappings(self.read())
    def test_placeholder_address_stops(self):
        self.config['modern_bluetooth']='02:00:00:00:00:00'
        with self.assertRaises(ValueError): mappings(self.read())
    def test_oversized_ssid_stops(self):
        self.config['wifi_ssid']='x'*33
        with self.assertRaises(ValueError): mappings(self.read())
    def test_source_injection_stops(self):
        self.config['modern_serial']='bad";value'
        with self.assertRaises(ValueError): mappings(self.read())
    def test_staged_identity_and_local_edit_preservation(self):
        scripts=self.root/'tools';scripts.mkdir()
        for name in ('configure.py','local_config.py'):
            shutil.copyfile(Path(__file__).parent/name,scripts/name)
        source=self.root/'source';source.mkdir()
        cache=source/'__pycache__';cache.mkdir();(cache/'fixture.pyc').write_bytes(b'\xff\x00compiled')
        (source/'fixture.txt').write_text('@PIXEL_SERIAL@ @CALL_APP_UID@\n')
        self.read()
        command=[sys.executable,str(scripts/'configure.py')]
        subprocess.run(command,check=True,capture_output=True)
        self.assertFalse((self.root/'.local/work/__pycache__').exists())
        target=self.root/'.local/work/fixture.txt'
        self.assertEqual(target.read_text(),'AuditModern @CALL_APP_UID@\n')
        self.config['call_app_uid']=10001;self.read()
        subprocess.run(command,check=True,capture_output=True)
        self.assertEqual(target.read_text(),'AuditModern 10001\n')
        target.write_text('local edit\n')
        result=subprocess.run(command,capture_output=True)
        self.assertNotEqual(result.returncode,0)
        self.assertEqual(target.read_text(),'local edit\n')


if __name__=='__main__': unittest.main()

"""Offline failure checks for charging identity/boot inputs. No ADB or network."""
from pathlib import Path
import hashlib
import importlib.util
import unittest

path=Path(__file__).resolve().parents[1]/'source/charging/profile.py'
spec=importlib.util.spec_from_file_location('charging_profile',path)
profile=importlib.util.module_from_spec(spec);spec.loader.exec_module(profile)


class ChargingInputs(unittest.TestCase):
    def setUp(self):
        self.apk=b'Synthetic APK bytes for input validation; not installable'
        self.identity=dict(model='SPH-D710',build='IMM76I.FL24',kernel='3.0.15-1173404 Dec 24 2012',
                           package=profile.PACKAGE,uid=10001,apk_path='/data/app/'+profile.PACKAGE+'-1.apk',
                           apk_sha256=hashlib.sha256(self.apk).hexdigest())
    def test_valid_identity(self):
        self.assertEqual(profile.validate_identity(self.identity,self.apk)[0],10001)
    def test_both_android_apk_slots(self):
        self.identity['apk_path']=self.identity['apk_path'].replace('-1.apk','-2.apk')
        profile.validate_identity(self.identity,self.apk)
    def test_wrong_model(self):
        self.identity['model']='Other S2'
        with self.assertRaises(ValueError):profile.validate_identity(self.identity,self.apk)
    def test_wrong_build(self):
        self.identity['build']='Other firmware'
        with self.assertRaises(ValueError):profile.validate_identity(self.identity,self.apk)
    def test_wrong_kernel(self):
        self.identity['kernel']='3.0.15 generic'
        with self.assertRaises(ValueError):profile.validate_identity(self.identity,self.apk)
    def test_wrong_namespace(self):
        self.identity['package']='example.other'
        with self.assertRaises(ValueError):profile.validate_identity(self.identity,self.apk)
    def test_bad_uids(self):
        for uid in (None,True,0,9999,100000,'10001'):
            with self.subTest(uid=uid):
                self.identity['uid']=uid
                with self.assertRaises(ValueError):profile.validate_identity(self.identity,self.apk)
    def test_arbitrary_path(self):
        self.identity['apk_path']='/sdcard/app.apk'
        with self.assertRaises(ValueError):profile.validate_identity(self.identity,self.apk)
    def test_changed_apk(self):
        with self.assertRaises(ValueError):profile.validate_identity(self.identity,self.apk+b'changed')
    def test_unexpected_boot_script(self):
        with self.assertRaises(ValueError):profile.boot_script(b'#!/system/bin/sh\n# unknown modification\n')


if __name__=='__main__':unittest.main()

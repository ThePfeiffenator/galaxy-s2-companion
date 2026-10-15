"""Validate locally captured inputs before generating privileged artifact pins."""
import hashlib
import re

PACKAGE = 'org.retrobridge.callprobe'
STOCK_BOOT_SHA = 'd421241570b61c655d79e0a460f0395a4520c49411fc90b9b4e61b441bff1842'


def validate_identity(identity, apk):
    if identity.get('model') != 'SPH-D710' or identity.get('build') != 'IMM76I.FL24':
        raise ValueError('Unsupported model/build')
    kernel = identity.get('kernel', '')
    if not all(s in kernel for s in ('3.0.15-1173404', 'Dec 24', '2012')):
        raise ValueError('Unsupported kernel')
    if identity.get('package') != PACKAGE:
        raise ValueError('Wrong Companion namespace')
    uid = identity.get('uid')
    if type(uid) is not int or not 10000 <= uid <= 99999:
        raise ValueError('Capture the actual installed app UID')
    if identity.get('apk_path') not in ('/data/app/'+PACKAGE+'-1.apk', '/data/app/'+PACKAGE+'-2.apk'):
        raise ValueError('Unsupported installed APK path')
    sha = identity.get('apk_sha256', '')
    if not re.fullmatch('[0-9a-f]{64}', sha) or sha != hashlib.sha256(apk).hexdigest():
        raise ValueError('Installed APK hash differs from the locally built candidate')
    return uid, sha


def boot_script(original):
    if hashlib.sha256(original).hexdigest() != STOCK_BOOT_SHA:
        raise ValueError('Unexpected boot script: do not overwrite other boot modifications')
    return original + (b'\n# Galaxy Companion battery protection; independent of phone/network services.\n'
                       b'if [ -x /system/bin/galaxy-charge-manager ]; then\n'
                       b'  exec /system/bin/galaxy-charge-manager --run\nfi\n')

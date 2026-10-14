"""Materialize private build sources; never connects to a phone or the internet."""
from pathlib import Path
import argparse, hashlib, json, re

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.local/work'
TOKEN = re.compile(r'@[A-Z][A-Z0-9_]+@')

def mappings(config):
    values = {}
    for name in ('pixel_bluetooth', 's2_bluetooth'):
        value = config[name].upper()
        if not re.fullmatch(r'(?:[0-9A-F]{2}:){5}[0-9A-F]{2}', value):
            raise ValueError('Invalid ' + name)
        if value in ('00:00:00:00:00:00', '02:00:00:00:00:00'):
            raise ValueError('Android privacy placeholder is not a Bluetooth address')
        values['@' + name.upper() + '@'] = value
    if values['@PIXEL_BLUETOOTH@'] == values['@S2_BLUETOOTH@']:
        raise ValueError('The phones must have different addresses')
    values['@PIXEL_BLUETOOTH_LE@'] = ','.join('0x' + b.lower() for b in config['pixel_bluetooth'].split(':')[::-1])
    for name in ('s2_serial', 'pixel_serial', 'wifi_ssid'):
        value = config[name]
        # Inserted into C, Java, Python and shell literals: reject metacharacters.
        if not re.fullmatch(r'[A-Za-z0-9 _.-]{1,64}', value) or value.startswith('REPLACE_'):
            raise ValueError('Provide a plain alphanumeric ' + name)
        values['@' + name.upper() + '@'] = value
    for name in ('app_signer_sha256', 'identity_jar_sha256'):
        value = config.get(name)
        if value is not None:
            if not re.fullmatch('[0-9a-f]{64}', value): raise ValueError('Invalid ' + name)
            values['@' + name.upper() + '@'] = value
    uid = config.get('call_app_uid')
    if uid is not None:
        if type(uid) is not int or not 10000 <= uid <= 99999: raise ValueError('Invalid app UID')
        values['@CALL_APP_UID@'] = str(uid)
    return values

def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--config', type=Path, default=ROOT/'local.json')
    a = p.parse_args()
    values = mappings(json.loads(a.config.read_text(encoding='utf-8-sig')))
    receipt = ROOT/'.local/materialized.json'
    previous = json.loads(receipt.read_text()) if receipt.exists() else {}
    pending = []
    for src in sorted((ROOT/'source').rglob('*')):
        if not src.is_file(): continue
        rel = src.relative_to(ROOT/'source').as_posix()
        data = src.read_bytes()
        if src.suffix != '.png':
            text = data.decode('utf-8')
            for key, value in values.items(): text = text.replace(key, value)
            data = text.encode('utf-8')
        target = WORK/rel
        if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest() != previous.get(rel):
            if target.read_bytes() != data:
                raise SystemExit('Local edits preserved; reconcile before reconfiguring: ' + rel)
        pending.append((rel, target, data))
    hashes = {}
    for rel, target, data in pending:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        hashes[rel] = hashlib.sha256(data).hexdigest()
    receipt.write_text(json.dumps(hashes, indent=2)+'\n')
    unresolved = sorted({m for _, _, d in pending for m in TOKEN.findall(d.decode('utf-8', errors='ignore'))})
    print('Materialized', len(pending), 'source files into .local/work.')
    if unresolved: print('Native install identity still needs configuration:', ', '.join(unresolved))
    print('No downloads, installations, or device changes.')

if __name__ == '__main__': main()

"""Read private setup configuration; old pixel_* keys remain compatible."""
import json


def load(path):
    config = json.loads(path.read_text(encoding='utf-8-sig'))
    for suffix in ('serial', 'bluetooth'):
        modern, legacy = 'modern_' + suffix, 'pixel_' + suffix
        if modern in config and legacy in config and config[modern] != config[legacy]:
            raise ValueError('Conflicting modern/legacy configuration for ' + suffix)
        if modern in config:
            config[legacy] = config[modern]
        if legacy not in config:
            raise ValueError('Missing ' + modern)
    return config

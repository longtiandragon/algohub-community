"""Generate fresh local-only configuration. Never overwrite an existing config."""
import argparse
import os
from pathlib import Path
import secrets

parser = argparse.ArgumentParser()
parser.add_argument('--postgres-port', type=int, default=5433)
parser.add_argument('--redis-port', type=int, default=6380)
parser.add_argument('--instance', default='algohub')
args = parser.parse_args()
if not args.instance.replace('-', '').isalnum():
    parser.error('instance must contain only letters, digits and hyphens')
if not all(1024 <= p <= 65535 for p in (args.postgres_port, args.redis_port)):
    parser.error('ports must be between 1024 and 65535')
root = Path(__file__).resolve().parent.parent
env_file = root / '.env'
config = root / 'backend/local-secret/development.properties'
if env_file.exists() or config.exists():
    parser.error('configuration already exists; inspect it manually, no files overwritten')
db_password, admin_password = secrets.token_hex(24), secrets.token_hex(20)
env_text = (root / '.env.example').read_text(encoding='utf-8')
env_text = env_text.replace('REPLACE_WITH_RANDOM_LOCAL_PASSWORD', db_password)
env_text = env_text.replace('5433', str(args.postgres_port)).replace('6380', str(args.redis_port))
env_text = env_text.replace('algohub-postgres', args.instance + '-postgres').replace('algohub-redis', args.instance + '-redis')
config_text = (root / 'backend/development.properties.example').read_text(encoding='utf-8')
config_text = config_text.replace('REPLACE_WITH_RANDOM_LOCAL_PASSWORD', db_password)
config_text = config_text.replace('REPLACE_WITH_RANDOM_LOCAL_ADMIN_PASSWORD', admin_password)
config_text = config_text.replace('5433', str(args.postgres_port)).replace('6380', str(args.redis_port))
config.parent.mkdir(parents=True, exist_ok=True)
for path, content in ((env_file, env_text), (config, config_text)):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, 'w', encoding='utf-8') as stream:
        stream.write(content)
print('Created .env and backend/local-secret/development.properties. Read the latter locally for the admin password; do not share it.')

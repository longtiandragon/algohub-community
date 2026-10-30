# QOJ collection and ownership verification

QOJ uses a hybrid flow: the server verifies the exact, expiring Motto challenge
and reads the profile total; the user's browser imports their own visible AC
records. Verified browser records participate under the same rules as Luogu,
while retaining USER_REPORTED provenance. Users never supply cookies/passwords.

The collector connects only to the dedicated local Chrome profile created by
`../qoj-browser/`. It does not export cookies, HTML, storage, or credentials. Its
HTTP interface listens on 127.0.0.1:9224 and requires a random service token.
Install requirements into `/opt/algohub-qoj-collector/venv`, copy collector.py
there, and install the supplied systemd unit. Run as qoj-browser, never root.
The token belongs at `/etc/algohub-qoj-collector/token` (root:qoj-browser,0640).
Keep a separate identical copy at `/etc/algohub/secrets/qoj-collector-token`
(root:algohub,0640), without granting the application access to the browser profile.

Application configuration:

```
ALGOHUB_QOJ_SERVER_ENABLED=true
ALGOHUB_QOJ_COLLECTOR_TOKEN_FILE=/etc/algohub/secrets/qoj-collector-token
```

Serialize requests, with at least two seconds between reads. Expired login,
access denial, and rate limiting pause collection for five minutes. Network
failures pause it for thirty seconds. Existing statistics remain available.
An administrator renews expired login in the private remote browser; captcha
and second-factor checks require manual completion. No repeated password login
attempts are made. Do not expose VNC, Chrome debug ports, or this collector publicly.

## Deployment migration

Before deployment, record a UTC cutoff and counts of QOJ accounts/users. Back up
the database and verify the backup. After the new application, profile read,
and Motto capability work, execute `../migrate-qoj-motto.sql` with psql's cutoff
variable. It archives the active account and directly dependent records in the
protected database before removing the old binding. Account locks fence old
plugin/task writes; one SYSTEM notice per user uses a stable deduplication key.
Repeat with the same cutoff only. Never choose a later cutoff on a retry.

The archive can include encrypted credential rows and authorization hashes and
must remain under the same protection as the database backup. Do not export it
to Git, application logs, public artifacts, or customer-visible responses.
Recovery must be manual and reviewed: restoring old grants or upgrading old
plugin data to verified records is forbidden. New verification starts clean.

## Confirmed limits

The dedicated account sees fewer submissions than the owner when private
competition/upsolve permissions differ. Reaching the last visible page does
not prove complete history. Homepage totals and visible distinct AC counts can
differ. Only explicit AC labels qualify; a numeric 100 alone is insufficient.
Totals never generate submission timestamps, heatmap entries, or new activity.
The plugin currently imports AC snapshots; removal of AC after rejudging is not
automatically reconciled and requires follow-up before claiming rejudge support.

# CSLSM Web App — Plan v3 (Ubuntu server, multi-user, secured)

Spring Boot web app on an Ubuntu server, reachable only through your existing WireGuard VPN
from five approved machines. Three roles. Encrypted daily backups copied to your MacBook and/or
a USB drive, which nobody — not even the server — can open.
Same database, same Flyway migrations, same Excel import logic; only the UI layer is rewritten.

---

## 1. Roles

| Capability | Receptionist | Admin | Super Admin |
|---|---|---|---|
| Upload daily log Excel files (browser upload, any time) | ✅ | ✅ | ✅ |
| Add day-to-day expenses (supplies, drinks, small repairs…) | ✅ | ✅ | ✅ |
| See today's / this week's income totals | ✅ | ✅ | ✅ |
| See profit, balance, safe, reports | ❌ | ✅ | ✅ |
| Salaries, utilities, deposits, withdrawals, safe & bank movements | ❌ | ✅ | ✅ |
| Approve receptionist expenses, delete records | ❌ | ✅ | ✅ |
| Create users, assign roles, approve devices, settings, audit log, backup status | ❌ | ❌ | ✅ |

Roles are enforced server-side on every request, not only in the menu.
Receptionist lands on the Expenses page and never sees a money total beyond the day's income.
`Entered by` is filled from the login; `Approved by` is set when an admin approves.

---

## 2. Pages

1. **Dashboard** (admin+) — this month's income, expenses, **net profit** with % vs last month;
   cash cards: reception balance, safe, sent to bank; 12-month income-vs-expenses chart;
   attention list: days without a log file, expenses pending approval.
2. **Income** — one page with a period selector (day / week / month / year / custom),
   activity breakdown, payment mix, daily-or-monthly chart, compare-to-previous-period toggle.
   Replaces the five period tabs.
3. **Daily logs** — drag-and-drop upload of `DL-dd-MM-yyyy.xlsx` (receptionist+), import
   status per file, failed files with the reason, re-upload. Replaces the watched folder.
4. **Expenses** — table with filters, entry form, approval status, receipt photo attachment,
   Excel import. Admin-only categories (salaries, rent, insurance…) are hidden from receptionists.
5. **Cash & Reserves** (admin+) — reception balance and safe with their movements; transfer to
   safe, send to bank, deposits, withdrawals, set balance.
6. **Reports** (admin+) — monthly P&L, expenses by category and by activity, **profit per
   activity**, payment mix, year-over-year; CSV/Excel export.
7. **Administration** (super admin) — users & roles, devices, audit log, backup status,
   year-mode setting.

Dropped: Compare Days. Folded into Reports: Compare Months, Payments Analysis. Trends becomes
the chart in Income.

---

## 3. Suggested improvements (beyond parity)

Ranked by value for the effort:

1. **Expense approval workflow** — receptionist entries are *pending* until an admin approves.
   The `approved_by` column already exists; this makes it meaningful and gives you control
   over what goes into the P&L.
2. **Missing-day detection** — the dashboard lists dates with no log file. Today a forgotten
   file silently understates income.
3. **Profit per activity** — income of Padel minus expenses allocated to Padel. You already
   capture the allocation; this is the payoff.
4. **Receipt attachments** — photo or PDF on an expense, stored on the encrypted data volume,
   shown inline. Turns the expense list into something an accountant can audit.
5. **Audit log** — who created/edited/deleted/approved what, when, from which device.
6. **Recurring expense templates** — one click adds "July salaries" pre-filled from last month;
   you still confirm each one (no automatic posting).
7. **Import sanity checks** — reject a file whose department totals do not add up to TTC, or
   whose date is in the future / already imported, instead of silently storing wrong numbers.
8. **Daily summary message** — at closing time, an email or Telegram message to admins with
   the day's income, expenses and whether the log file was uploaded.
9. ~~**Bank as a tracked account**~~ — built: two tracked accounts (the Association's and Tiki
   Taka's) with statement corrections, transfers and other movements.

---

## 4. Machines

| Machine | Role in the system | Who can log in from it |
|---|---|---|
| **Ubuntu server** | Runs the app, nginx, nightly backups | Nobody via browser; SSH from admin Macs only |
| **Reception PC 1** (Windows) | Client | Receptionists, admins |
| **Reception PC 2** (Windows) | Client | Receptionists, admins |
| **Mac Server** | Client; always-on backup destination | Admins, super admin |
| **MacBook Pro — yours** (`10.120.0.2`) | Client; backup destination | Super admin, admin |
| **MacBook Pro 2** | Client — Finance manager | Admin (Finance manager) |

**One-time setup per client** (~10 minutes each): WireGuard tunnel (already done), install the
internal CA certificate, install that machine's own client certificate, bookmark the app URL.

**Reception PCs (Windows, shared desk)**
- WireGuard runs as a boot-time service, so the tunnel is always up without staff doing anything.
- Staff use non-admin Windows accounts: they cannot stop the tunnel or export the certificate
  (imported as non-exportable).
- Use Edge or Chrome — they read the Windows certificate store; Firefox keeps its own.
- Each receptionist has her own app account (so *entered by* is accurate); receptionist
  sessions end after 15 minutes idle.

**MacBooks that leave the building** — the tunnel works from anywhere, FileVault must be on.
If one is lost: Administration → Devices → Revoke (kills its certificate and open sessions),
then remove its WireGuard peer. Takes a minute; the other machines are unaffected.

---

## 5. Security design

Defense in layers. Any single layer failing still leaves the app closed.

**Layer 1 — network: only the five approved machines reach the app (existing WireGuard)**
- Each client keeps its own WireGuard key and fixed tunnel IP. WireGuard's cryptokey routing
  guarantees that a packet from a peer's tunnel IP really comes from the machine holding that
  peer's key — so the tunnel IP is a trustworthy device identity.
- nginx listens **only on the server's WireGuard address**; the app itself only on
  `127.0.0.1`. A PC on the same office LAN still cannot reach it without the tunnel.
- ufw: default deny; `443` allowed on `wg0` from the five client tunnel IPs only; SSH on `wg0`
  from the admin Macs only. Your WireGuard setup itself is left untouched.
- **Why not MAC addresses**: a MAC address only exists on the local Ethernet/Wi-Fi segment and
  never crosses a router or a VPN, so the server cannot see it — and it is trivial to spoof.
  WireGuard keys plus client certificates give the per-machine control you asked for, properly.

**Layer 2 — transport: TLS with per-machine client certificates**
- nginx serves HTTPS with a certificate from a small internal CA created at install time.
- **Mutual TLS**: each approved machine holds its own client certificate; nginx rejects any
  connection without a valid, non-revoked one. A second device check, independent of WireGuard.
- `cslsm-device add "Reception PC 1"` issues a certificate bundle; `cslsm-device revoke …`
  updates the revocation list and reloads nginx.
- Security headers (HSTS, CSP, no-sniff, frame-deny), rate limiting on `/login`.

**Layer 3 — application**
- Spring Security: form login, Argon2 password hashes, secure/HttpOnly/SameSite cookies,
  CSRF on every form, lockout after 5 failed attempts.
- **TOTP two-factor** (Google Authenticator style) mandatory for admin and super admin.
- **Device-bound accounts**: nginx passes the verified certificate name to the app, and each
  user has a list of machines they may log in from. Defaults follow the table in §4 — a
  receptionist's password is useless from a MacBook, the super admin's useless from reception.
- Method-level role checks on every controller; a receptionist calling an admin URL gets 403.
- Uploads: extension + content check, size limit, stored outside the web root under a random
  name, parsed with the same rules as the desktop importer.
- Audit log of every write: user, machine (certificate name + tunnel IP), timestamp, before/after.

**Layer 4 — server**
- Ubuntu 24.04 LTS, `unattended-upgrades` on, SSH by key only, fail2ban.
- App runs as an unprivileged `cslsm` user under systemd with hardening
  (`ProtectSystem=strict`, `PrivateTmp`, `NoNewPrivileges`, read-only code, writable data dir only).
- Data directory on an encrypted volume (LUKS) so a stolen disk is useless.
- Secrets in `/etc/cslsm/`, mode 600, readable only by the service.

---

## 6. Backups — encrypted, with configurable destinations

### How it works
1. **Every night** (time configurable) the server takes a consistent snapshot of the database,
   the log-file folders and receipt attachments, streams it through `tar` straight into
   **`age` encryption**, and stores one file: `cslsm-2026-09-29.tar.age` plus a checksum.
   Unencrypted data never leaves the encrypted data volume.
2. The file is then delivered to whichever **destinations** are enabled in
   `/etc/cslsm/backup.conf`:

```ini
BACKUP_TIME=03:00
KEEP_DAILY=30          # on the server and on each destination
KEEP_MONTHLY=12
DESTINATIONS="macbook macserver usb"   # any combination of: macbook  macserver  usb
USB_UUIDS="…"                           # only these registered drives are accepted
```

| Destination | How it works |
|---|---|
| **Your MacBook** (`10.120.0.2`) | The MacBook **pulls**: a background job checks hourly and, whenever the tunnel is up, downloads any archive it doesn't have yet. Laptop asleep or away for days → it catches up next time. |
| **Mac Server** | Same pull job, but it is always on — so a copy leaves the server every night even when your laptop doesn't. |
| **USB drive on the Ubuntu server** | Plug in a registered drive → new archives are copied and verified, the drive is unmounted, and the app shows *"USB copy done — safe to remove"*. Mounted `noexec,nosuid,nodev`; unregistered drives are ignored. |

**Pull, not push, on purpose**: the server holds no credentials to your laptop or Mac Server.
Each Mac's SSH key is locked on the server to a read-only account restricted to the backup
folder — it can download encrypted files and nothing else.

### Why nobody can open them
- The **decryption key is created on your MacBook, never on the server**, and is itself
  protected by a passphrase. Only its public half is installed on the server, which can
  encrypt backups but cannot decrypt them.
- Anyone who gets a backup file — from a stolen USB drive, laptop or server — sees random bytes.
- **Recovery key**: every backup is also encrypted to a second key, printed on paper and kept in
  the safe (or with your accountant). Without it, losing your laptop and forgetting the
  passphrase would make every backup permanently unreadable. `age` supports multiple
  recipients natively; either key alone can restore.

### Monitoring and restore
- Administration page: last backup, size, last successful copy per destination, next run.
- The super admin's dashboard shows a warning if any enabled destination's newest copy is
  older than 48 hours.
- `cslsm-restore <file>` (runs on a Mac or the server, needs key + passphrase): verifies the
  checksum, decrypts, shows the data's date range, restores to a new location — never
  overwrites the live database without explicit confirmation.
- Restore drill once a quarter; the procedure is in the hand-over doc.

---

## 7. Technical shape

- **Spring Boot 3.5, Java 21**, Thymeleaf, server-rendered SVG charts, hand-written CSS — no
  CDN or third-party front-end code, which keeps a strict Content-Security-Policy possible
  and lets the server run with no internet access. HTMX added only where a page needs it.
- **Reused as-is**: `DailyRepo`, `ExpenseRepo`, `CashRepo`, `SafeRepo`, `SettingsRepo`, models,
  migrations V1–V8, the Excel parsing of `ImportService` (folder watcher replaced by upload).
- **Database: SQLite stays** (WAL mode). At six machines it is more than enough, keeps every
  migration and repo untouched, and makes backup a single-file snapshot. PostgreSQL only if
  you ever grow well beyond this — the repos are plain JDBC, so that would be a contained change.
- New migrations: `V9__app_user` (users, roles, TOTP, lockout, allowed devices),
  `V10__audit_log` (append-only), `V11__expense_workflow` (pending/approved/rejected, paid from
  reception/safe/bank), `V12__expense_option` (category and activity lists),
  `V13__expense_attachment`, `V14__daily_import`, `V15__activity_rules` (cost rules per
  activity, expense splits), `V16__activities_v2`, `V17__password_change_required`, `V18__salon`, `V19__bank_accounts`, `V20__day_sheet` (withdrawn by `V21__three_daily_logs`, which keeps the Box/Danse
  columns and the frequent expenses); the device table comes with M4 as `V22`.
- Server layout: `/opt/cslsm/` (jar + scripts), `/var/lib/cslsm/` (data, on LUKS),
  `/etc/cslsm/` (config and secrets), `/var/backups/cslsm/` (encrypted archives only).
- `install.sh` sets up the service user, folders, systemd units, nginx + internal CA,
  ufw rules around your existing `wg0`, and the backup timer in one run.
- `mac-backup-setup.sh` installs the pull job on your MacBook (and on the Mac Server if enabled).

---

## 8. Sequence

| Milestone | Contents | Rough size |
|---|---|---|
| **M1** ✅ built | Skeleton, login + 3 roles + 2FA, Dashboard (read-only), Cash & Reserves display | ~3 days |
| **M2** ✅ built | Expenses (CRUD, filters, approval, receipts, sheet import, bank-deposit fix), daily-log upload with checks, Cash & Reserves actions, audit log | ~3 days |
| M3a ✅ built | Drinks as income; activity analysis (profit per activity, shared-cost rules, expense splits, activity settings, old-spelling clean-up) | — |
| M3b ✅ built | Activity structure v2: cost headings, staff with pay splits + payroll, allocation keys for utilities/common costs, Tiki Taka (restaurant manager role, daily sales, own till), Salon (daily sales entered by the reception) | — |
| **M3c** ✅ built | Three daily logs (center DL-, Tiki Taka TT-, Salon SA-) with templates; Box and Danse departments; frequent expenses; two tracked bank accounts; chart tooltips | — |
| **M3** ✅ built | Income page, Reports (P&L, expenses by category/activity, payment methods, year-over-year, profit by activity; Excel/CSV), audit log viewer, Administration page (accounts with temporary passwords, categories, year mode), own-password page | — |
| M4a ✅ built | Trial deployment on the Mac (`deploy/mac/install-mac.sh`): launchd service, HTTPS with a self-signed certificate, copy of the database, `cslsm-web` control script | — |
| **M4** | `install.sh`: systemd, nginx + mTLS + device certificates, ufw on `wg0`, device-bound logins | ~2 days |
| **M5** | Encrypted backups: server job, MacBook/Mac Server pull, USB handling, restore script, drill | ~1–2 days |
| **M6** | Cut-over: import the existing database, enroll the 5 machines, retire the JavaFX app | ~1 day |
| M7 (opt.) | Recurring templates, daily summary message (bank accounts: built) | ~1 day |

The desktop app keeps working until M6. The web app runs on a copy of the database until you switch.

---

## 9. Decisions

- **VPN**: your existing WireGuard; nothing reinstalled. M4 only adds firewall and nginx rules
  around it.
- **Backups**: encrypted on the server, then copied to your MacBook (`10.120.0.2`), the Mac
  Server (always-on nightly copy) and a USB drive plugged into the Ubuntu server. Each
  destination can be switched on or off in `backup.conf`.
- **Machines**: 1 Ubuntu server + 5 clients (2 Windows reception PCs, Mac Server, 2 MacBook Pros).
  Your MacBook is `10.120.0.2`; it holds the backup decryption key.
- **Framework**: Spring Boot 3.5 line (the currently supported 3.x line) instead of 3.3.

- **Second MacBook**: the Finance manager's, with an Admin account.

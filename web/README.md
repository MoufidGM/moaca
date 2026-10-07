# CSLSM Web — Milestones 1 and 2

**M1:** login with three roles and two-factor codes; the Today, Dashboard and Cash & Reserves pages.

**M2:**

- **Expenses.** Receptionists enter day-to-day expenses, which wait for an admin's approval.
  Admins enter everything else (salaries, bills, rent) and approve or reject what reception
  entered. Receipts (photo or PDF) can be attached. Admins can import an Excel sheet after
  checking a preview.
- **Daily logs.** Staff upload files from the browser, and the checks run on every file. An
  admin can replace a day that was already imported.
- **Cash & Reserves actions.** Moving cash to the safe, taking it to the bank, payments,
  deposits, withdrawals, correcting a balance to the amount counted, and deleting a movement.
- **Audit log.** Every change, sign-in and failed attempt is recorded, and the log can't be
  edited or deleted.

**Added since:** drinks count as income, and a per-activity cost and profit analysis with
expense splits and activity settings.

**M3:**

- **Income.** Any day, week, month, year or custom range: total, per-day average, best day,
  breakdown by activity and by payment method, a chart, and the previous period beside it.
- **Reports.** Profit & loss, expenses by category and by activity, payment methods, year over
  year and profit by activity, per year, each downloadable as Excel or CSV.
- **Administration** (super admin). Accounts with temporary passwords, expense categories, the
  year-mode setting and the audit log viewer. Everyone can change their own password.

It works on the same database as the desktop app. Migrations V9–V23 only *add* tables and
columns (V16 also renames three activities), and the desktop app keeps working after them.

## Try it on your Mac

Needs JDK 21 and Maven (IntelliJ provides both).

**1. Work on a copy of the database** so testing never touches the real one:

```bash
cp ../data/daily_logs.db ../data/web-dev.db
```

**2. Build** (this also runs the tests):

```bash
cd web
mvn package
```

**3. Create your super admin account.** Run this in the macOS Terminal. The IntelliJ run
console can't show a hidden password prompt.

```bash
java -jar target/cslsm-web.jar --spring.profiles.active=dev \
     --create-user=mo --role=SUPER_ADMIN --name="Moufid"
```

For testing, also create a receptionist and an admin:

```bash
java -jar target/cslsm-web.jar --spring.profiles.active=dev --create-user=desk --role=RECEPTIONIST
java -jar target/cslsm-web.jar --spring.profiles.active=dev --create-user=admin --role=ADMIN
```

**4. Start the app**:

```bash
java -jar target/cslsm-web.jar --spring.profiles.active=dev
```

Or, in IntelliJ, run `CslsmWebApplication` with the active profile set to `dev`.

Then open <http://localhost:8080>. The first time an admin or super admin signs in, the app
shows a QR code. Scan it with Google Authenticator, Microsoft Authenticator or 1Password, then
type the 6-digit code.

## Three daily logs

Days come from Excel files, one per unit, all uploaded on **Daily logs**:

| File | For | Who uploads | What is read |
|---|---|---|---|
| `DL-dd-MM-yyyy.xlsx` | The center (the sheet in use since 2025) | Reception, admins | Department totals, cash/card/cheque, drinks |
| `TT-dd-MM-yyyy.xlsx` | Tiki Taka | Restaurant manager, reception, admins | Sales, card, the family's meals, the till's expenses, covers, note |
| `SA-dd-MM-yyyy.xlsx` | The Salon | Reception, admins | Total sales, card, clients, note — cash is the rest. Lines carry the time, the staff member and any discount |

**Tiki Taka's file** has three blocks under the summary: *Ventes* (one line per dish or drink,
price × quantity; events as a line with the amount), *Famille / sur compte* (one line per meal
a family member ate, name and amount) and *Dépenses payées de la caisse* (object, category,
amount). On upload the app records the sales (cash = total − card − family), one line per
family meal — nobody pays those, so they are neither cash nor revenue, only shown per person
on the Tiki Taka page for the month and the year — and creates each till expense as a Tiki
Taka expense paid from the restaurant till (waiting for approval when the manager uploads).
A replaced file replaces its meals and expenses. A family or expense line with an amount but
no name refuses the file, so it gets fixed rather than half-imported.

**What the reception sees:** Daily logs (the three files and the expense sheets) and Expenses
(its own entries, manual or by sheet). No totals: Today, the Salon page and everything else
with money are for admins. The restaurant manager sees Tiki Taka, Daily logs (TT- files) and
Expenses.

The Tiki Taka and Salon files start from the templates linked on the page (and on their own
pages): a summary block the app reads, and a lines table (name, price, quantity) whose sum
feeds it. The same checks apply as for the center's file: refused for a bad name, a future
date, not Excel, no figures, or a day already imported (admins can tick *Replace*); imported
with a warning when card is more than the total, an amount is negative, or the file is
byte-identical to another day's. Tiki Taka's and the Salon's pages keep a form to type the
day's totals directly for days without a file.

Box and Danse are departments of their own (new columns, V20). The center's sheet does not have
them yet; when it does, set `summary.col.box` and `summary.col.dance` in
`daily-log-layout.properties` and they are read like the others.

**Expense sheets** (Expenses → *Import sheet*, or from Daily logs): anyone can upload one, saying
whether it is the center's, Tiki Taka's or the Salon's. Every row is checked and shown before
anything is saved. An admin's rows are saved approved; the reception's and the manager's wait
for approval like manual entries, come from the till (the restaurant's for Tiki Taka), and may
only use the reception's categories. Tiki Taka's and the Salon's sheets land on their activity.

**Uploaded files** (admins, *All uploaded files* on Daily logs or Administration): every daily
log and expense sheet ever uploaded, sorted by the date it is for, with the file to download,
who uploaded it and the result. Originals are kept on the server's data volume.

**Frequent expenses:** on any expense, an admin presses *Add to frequent expenses*; the expense
form then shows a one-tap button that fills category, activity, amount and source. The super
admin switches them off on the Administration page.

## Income includes drinks — and Tiki Taka

A day's income is the daily log's total (the departments) **plus drinks**, everywhere in the
app: Today, Dashboard, Income, the charts, the reception balance and the activity analysis.
Drinks are assumed to be paid in cash at the bar, so they add to the reception balance.

For admins, the center's income also includes **Tiki Taka's and the Salon's sales** (Dashboard,
Income, Reports, Activities), just as their expenses are counted, so the net profit is the whole
center's. The Today page stays the daily log's. The reception balance is the daily log's income
plus the Salon's cash (handed to the reception); the restaurant has its own till.

About 41,800 of drinks sales since April were never recorded, because the desktop importer read
the wrong cell. To recover them, an admin uploads the April–September files again on *Daily logs*
with **Replace days already imported** ticked, up to 100 at a time. Skip the `(1)` copies unless
they're the corrected version.

## Activity structure (V16)

Activities: **Location de terrains, Académie, Gym, Arts Martiaux, Padel, Ping Pong, Park, Mini Golf,
Shoes, Drinks** (from the daily log), **Tiki Taka** (the restaurant, from its own daily sales) and
**Salon** (its daily sales entered by the reception).
Each activity's page breaks its costs down into:

| Line | Where it comes from |
|---|---|
| Salaries of its own staff | Salary expenses of employees whose pay is 100% this activity |
| Share of other salaries | Employees whose pay is split (e.g. security 70% terrains / 30% gym) |
| Maintenance and services | Maintenance, Cleaning categories |
| Purchases and equipment | Supplies, Equipment, Packs & jerseys, Insurance, Food & drinks |
| Electricity, water, phone, internet | Bills on the activity, plus its share of bills on a shared activity |
| Share of common costs | Everything else recorded on a shared activity (General, Technical…) |

**Staff** (admin menu): each employee is created once with a job, an optional monthly salary and
how their pay divides between activities. **Payroll** records one approved salary expense per
employee for a month, already split. Salary advances entered as expenses also name the employee.

**Allocation keys** (Activity settings): percentages per activity for utility bills, and for
other common costs. Without a key the pool is spread by revenue (or equally, or not at all,
as chosen on the Activities page).

**Tiki Taka**: the *Restaurant manager* role only sees the Tiki Taka page and the Expenses page.
It records each day's cash and card sales (correctable for 7 days) and enters restaurant
expenses, which are always Tiki Taka's and paid from the **restaurant's own till**. Admins move
restaurant cash to the safe or the bank from that page. The restaurant appears as its own section
on the Activities page, with its sales as revenue.

Create the manager's account with `--create-user=chef --role=RESTAURANT_MANAGER`.

**Salon**: the reception records the Salon's cash and card sales each day on the *Salon* page
(correctable for 7 days; admins any day, and only admins delete a day). The cash goes into the
reception till with the day's income; card sales go to the bank. Salon expenses are ordinary
expenses on the *Salon* activity, so the Salon has its own line with revenue, costs and profit on
Activities, Income and the reports.

## Bank accounts

Two accounts are tracked on **Cash & Reserves**, all time like the safe:

| | Fed by | Pays |
|---|---|---|
| **Association bank account** | The daily log's card and cheque payments, the Salon's card payments, cash taken to the bank from the reception and the safe | Expenses marked *Association bank account* |
| **Tiki Taka bank account** | The restaurant's card sales, cash taken from its till to the bank | Expenses marked *Tiki Taka bank account* (the payroll can pay from it too) |

What the app cannot know on its own is recorded on each account: other money in (subsidies,
refunds), other payments (bank fees), transfers between the two accounts, and *Correct to the
balance on the bank statement*, which records the difference as a correction — do this once
with the first statement, then whenever the figures drift. The Dashboard shows both balances
(hidden until the eye is pressed); the Tiki Taka page shows its own.

## Activity analysis (admins)

**Activities** shows revenue, costs and profit per activity for any period, and each activity
has its own page. The page shows a 12-month chart, costs by category, average revenue per
weekday, best and weakest days, and every expense behind the figures.

**How each activity's costs count** is set on **Activity settings**:

| Rule | Meaning | Default for |
|---|---|---|
| Earns revenue | Revenue comes from its daily-log column, or from its own sales page (Tiki Taka, Salon); its expenses are direct costs | the 9 departments + Drinks, Tiki Taka, Salon |
| Shared by all activities | Common costs, spread over the activities that earn revenue | General, Cleaning, Technical |
| Its own line | Costs shown separately, no revenue | Danse, Events |
| Part of … | Its costs count for an activity that earns revenue | — |

- **Common costs** are spread in proportion to revenue by default. The page can also show them
  spread equally, or on their own line.
- **Splitting an expense (admins):** on the expense form, *Split across several activities*
  splits one expense by percentage (e.g. electricity 60% Terrain, 40% Gym). The split lines
  must add up to 100%.
- **Old spellings:** activity names typed freely in the desktop app ("T-Foot", "Menage",
  "Tournoi"…) are listed on the settings page with a suggested activity. *Apply suggestions*
  merges them all in one click, and each merge is recorded in the audit log. Names with no
  suggestion ("G3K", "Dior Atlas") are yours to place, or to keep as new activities.
- **The rows always add up:** the sum of every row's profit equals the center's income minus
  its expenses for the period.

## Income and Reports (admins)

**Income** shows the center's income — departments, drinks, Tiki Taka and the Salon, the same
figure as the Dashboard — for a day, a week (Monday to Sunday), a month, a year or a custom range, with
‹ › to move through periods. *Compare with previous period* puts the previous period beside
it: a running month against the same days of last month, a finished month against the whole
previous month, a day against the same weekday a week earlier. Days without a log file are
listed. Tiki Taka and the Salon are lines among the activities; their cash and card sales are
separate lines of the payment mix.

**Reports** are per year, with a month per column:

| Report | Contents |
|---|---|
| Profit & loss | Revenue by activity plus Tiki Taka, expenses by heading, net profit and margin |
| Expenses by category / by activity | Every category or activity, month by month (split expenses counted on each part) |
| Payment methods | Cash, card, cheque, drinks, and Tiki Taka's and the Salon's cash and card, with the cash and card shares |
| Year over year | Each month against the same month of the previous year (a running month up to the same day) |
| Profit by activity | The Activities page's figures for the whole year |

*Download Excel* / *Download CSV* give the same cells as the screen. Revenue is the center's
income, Tiki Taka and the Salon included, so the net profit matches the Dashboard and the Activities page.
Months that have not started are left empty. Every download is written to the audit log.

## Administration (super admin)

**Accounts.** New accounts get a temporary password typed by the super admin; the person must
replace it at their first sign-in and can only open the *Password* page until they do. The
same applies after *Set temporary password* for a forgotten password. *Reset two-factor*
covers a lost phone; *Unlock now* ends a lockout early. Accounts are switched off, never
deleted, and a changed role or a switched-off account ends the person's open session at
their next click. The super admin cannot change their own role, switch themselves off, or
remove the last super admin who can sign in.

**Expense categories.** Add categories, mark them *admins only*, switch them off, order them,
and set the heading they count under in each activity's cost breakdown. *Salaries* and
*Salary advance* stay in use under Salaries: payroll relies on them.

**Reception balance and the new year.** The `storage.year_mode` setting shared with the
desktop app: carry the balance over, or restart it every 1 January.

**Audit log.** Every entry, newest first, with filters on date, user, action and text.

Everyone can change their own password by clicking their name in the top bar.

## Expense rules

| | Receptionist | Admin |
|---|---|---|
| Categories | Supplies, Maintenance, Cleaning, Transport, Salary advance, Other | All, including Salaries, bills, Rent, Taxes, Equipment |
| Date | Today, or up to 7 days back | Any past date |
| Money came from | Always the reception | Reception till, restaurant till, safe, Association bank account or Tiki Taka bank account |
| After saving | *Pending* until an admin approves it | Approved |
| Can see | Her own entries (last 60 days) | Everything, with filters |
| Can change | Her own entries while they're pending | Anything except rejected entries |

Nobody can date an expense in the future. Pending expenses count in the figures until an
admin rejects them; rejected ones never count.

**Bank deposits recorded as expenses:** open the expense and press *This is a bank deposit*.
This moves the amount to "sent to bank" and takes it out of the profit. The balance doesn't
change.

## Daily-log checks

Files are **refused** for a bad file name, a date in the future, a file that isn't Excel, no
figures in the expected cells, or a day that's already imported. After a receptionist uploads
a day, only an admin can replace it (with the *Replace days already imported* box).

Files are **imported with a warning** when:
- cash + card + cheque ≠ total
- the departments ≠ total
- a figure is negative
- the file is byte-identical to another day's file
- the total is more than 3× or less than a fifth of a typical day

Warnings stay on the dashboard until an admin marks them as checked.

The drinks total is read from its "Total" label rather than a fixed cell, so adding rows to
the template doesn't break it. Drinks are shown apart from the daily total, because the file's
own total leaves them out.

## Account commands (server terminal)

| Command | Use |
|---|---|
| `--create-user=NAME --role=RECEPTIONIST\|ADMIN\|SUPER_ADMIN [--name="Full name"]` | New account (password asked at a hidden prompt) |
| `--reset-password=NAME` | Forgotten password (also unlocks the account) |
| `--reset-2fa=NAME` | Lost phone: the user scans a new QR code at next login |
| `--unlock=NAME` | Unlock after 5 failed attempts without waiting 15 minutes |

These commands run without starting the web server, so they work while the service is up.
They are the recovery path when no super admin can sign in; day to day, accounts are managed
on the Administration page.

## What's enforced

- **Roles** are checked on the server twice: in the URL rules (`SecurityConfig`) and on each
  page's controller. The menu only mirrors those rules. On every request the account is
  re-read from the database, so a switched-off account or a changed role takes effect at once.
- **Two-factor codes** are required for admin and super admin. Each code works only once, and
  the two-factor secrets are stored encrypted with `CSLSM_SECRET_KEY`.
- **Lockout**: 5 wrong passwords *or* wrong codes lock the account for 15 minutes. The counter
  resets only after a complete sign-in, so knowing the password doesn't give unlimited code
  guesses.
- **Sessions**: receptionists are signed out after 15 minutes idle, admins after 60. The
  cookie is HttpOnly and SameSite=Strict, and Secure in production.
- **Headers**: a strict Content-Security-Policy (no external scripts, styles or fonts),
  frame-deny, no-referrer, and HSTS over HTTPS.
- **Passwords** are hashed with Argon2id and must be at least 12 characters.

## Deploy on your Mac (trial run)

Before the Ubuntu server exists, the app can run on the Mac as a real service: its own
folder, HTTPS, started at login and restarted if it stops, on a **copy** of the database.
The desktop app and `data/daily_logs.db` are not touched.

```bash
deploy/mac/install-mac.sh
```

This builds the jar, creates `~/cslsm-web` (`app/`, `data/`, `files/`, `config/`, `logs/`,
`backups/`), generates the secret key and a self-signed certificate for
`<this-mac>.local` and the current Wi-Fi address, copies the database, registers the
launchd agent `com.cslsm.web` and waits until the app answers. It then prints the address,
`https://<this-mac>.local:8443`. The browser warns about the certificate once; continue.
Other machines on the same network can open the same address (macOS may ask once whether
`java` may accept incoming connections: allow).

The first time, create the super admin from the terminal (hidden password prompt):

```bash
~/cslsm-web/cslsm-web user --create-user=mo --role=SUPER_ADMIN --name="Moufid"
```

Then sign in, scan the QR code, and create the other accounts on the Administration page.

| Command | Use |
|---|---|
| `~/cslsm-web/cslsm-web status` | running or not, and the address |
| `~/cslsm-web/cslsm-web logs` | follow the log |
| `~/cslsm-web/cslsm-web stop` / `start` / `restart` | the service |
| `~/cslsm-web/cslsm-web user …` | account commands (see below) |
| `~/cslsm-web/cslsm-web backup` | consistent copy of the database into `backups/` |
| `~/cslsm-web/cslsm-web update` | after a code change: rebuild and restart (`--with-tests` runs the tests first) |
| `deploy/mac/install-mac.sh --fresh-copy` | start again from a new copy of `data/daily_logs.db` (web accounts are lost; the old trial database is kept in `backups/`) |
| `deploy/mac/install-mac.sh --new-certificate` | after the Mac's name or network address changed |
| `~/cslsm-web/cslsm-web uninstall` | remove the service; the folder stays |

Open the trial with the `.local` name or the IP, not `localhost`: the app sends an HSTS
header, and a browser that has seen it for `localhost` would then refuse the plain-http
development instance on port 8080.

## Production (M4 preview)

On the Ubuntu server the app listens on `127.0.0.1:8080` behind nginx. It reads these
settings from `/etc/cslsm/cslsm.env`:

```bash
CSLSM_DB_PATH=/var/lib/cslsm/data/daily_logs.db
CSLSM_FILES_DIR=/var/lib/cslsm/files          # daily-log originals and receipts
CSLSM_SECRET_KEY=<output of: openssl rand -base64 48>
```

In development (`dev` profile), files go to `../data/web-dev-files`.

The app refuses to start without `CSLSM_SECRET_KEY`. Keep the key safe: if it's lost, everyone
has to re-enroll their two-factor codes (with `--reset-2fa`). No data is lost.

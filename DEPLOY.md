# Running CSLSM without IntelliJ

## Build the app (one command)

From this folder, in Terminal:

```bash
./package-mac.sh --install
```

That builds `CSLSM.app` and copies it to `/Applications`. Open it once from Finder,
then right-click its Dock icon → Options → Keep in Dock.

Without `--install` the app is left in `dist/CSLSM.app` and you can double-click it there.

The build takes a couple of minutes; it bundles its own Java runtime, so the app runs
even if you uninstall the JDK or IntelliJ later.

## Where the data lives

Unchanged — the app reads and writes the folders in this project directory:

- `data/daily_logs.db` — the database
- `CSLSM/incoming` — drop Excel files here for auto-import
- `CSLSM/processed`, `CSLSM/failed`

The app is launched with `-Dcslsm.home=<this folder>`, so double-clicking it behaves
exactly like running from IntelliJ. **Don't move or rename this project folder** — if you
do, re-run `./package-mac.sh --install` from its new location.

## After you change the code

Re-run `./package-mac.sh --install`. IntelliJ still works as before for development.

## Backing up

Everything that matters is one file: `data/daily_logs.db`. Copy it somewhere safe
regularly (it also contains expenses, safe movements and settings).

## Troubleshooting

**"No JDK 21+ with jpackage found"** — use the one inside IntelliJ:

```bash
JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home" ./package-mac.sh --install
```

**"maven not found"** — `brew install maven`, or the script picks up IntelliJ's bundled copy.

**macOS says the app is damaged / from an unidentified developer** — the app is unsigned.
The script already clears the quarantine flag; if the warning still appears, run:

```bash
xattr -cr /Applications/CSLSM.app
```

then right-click the app → Open → Open.

**The app starts but shows no data** — the project folder was moved. Rebuild with
`./package-mac.sh --install` from the current location.

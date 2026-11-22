# DNS feed attribution and usage notes

AdSkip does not bundle third-party DNS blocklist snapshots in its APK. Feed files are downloaded only after the user explicitly chooses a source in the app, and parsed into that device's local SQLite database.

## HaGeZi Multi LIGHT

- Project: https://github.com/hagezi/dns-blocklists
- Download format: dnsmasq
- Primary endpoint: https://hagezi-mirror.dnsbunker.org/dnsmasq/light.txt
- Additional HTTPS mirrors: https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/dnsmasq/light.txt ; https://raw.githubusercontent.com/hagezi/dns-blocklists/main/dnsmasq/light.txt ; https://gitlab.com/hagezi/mirror/-/raw/main/dns-blocklists/dnsmasq/light.txt ; https://codeberg.org/hagezi/mirror2/raw/branch/main/dns-blocklists/dnsmasq/light.txt
- Project license: GPL-3.0; see the upstream repository's LICENSE and current disclaimer.
- The upstream provider supplies the lists as-is and does not warrant completeness or absence of false positives. Some inputs are third-party data with their own terms. Do not redistribute snapshots without reviewing the upstream license and applicable source terms.

## StevenBlack Unified Hosts

- Project: https://github.com/StevenBlack/hosts
- Download format: hosts
- Primary endpoint: https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts
- Additional HTTPS mirrors: https://cdn.jsdelivr.net/gh/StevenBlack/hosts@master/hosts ; https://fastly.jsdelivr.net/gh/StevenBlack/hosts@master/hosts ; https://gcore.jsdelivr.net/gh/StevenBlack/hosts@master/hosts
- The repository package is MIT-licensed, but its unified file aggregates records from several upstream projects with different licenses/attribution terms. Review the upstream README and per-source terms before redistributing or republishing a combined snapshot.

## AdSkip processing

- Only explicit domain records in supported dnsmasq and hosts formats are imported.
- Adblock exception rules, regular expressions, IP rules and unsupported directives are ignored rather than converted into block rules.
- Imported feeds are validated, bounded to 12 MiB / 120,000 domains, and stored separately from manual rules.
- Users may enable, disable, update or remove each supported feed independently.
- Downloads first try each configured HTTPS mirror, then use a small retry budget with backoff for transient network/server failures.
- The downloader enforces connection/read/overall timeouts, a 12 MiB streaming limit, HTTPS-only redirects and an allowlist of source mirror hosts.
- A failed, incomplete or untrusted download never replaces the previously imported copy.

These feeds are optional third-party data sources. They are not controlled or guaranteed by AdSkip. A blocklist may block legitimate services or miss unwanted domains. Use the lighter list first and keep manual rule backups before enabling broader coverage.

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

## AdAway Official Hosts

- Project: https://github.com/AdAway/adaway.github.io
- Download format: hosts
- Primary endpoint: https://adaway.org/hosts.txt
- Additional HTTPS mirrors: https://raw.githubusercontent.com/AdAway/adaway.github.io/master/hosts.txt ; https://cdn.jsdelivr.net/gh/AdAway/adaway.github.io@master/hosts.txt
- AdAway is an independently maintained Android ad-blocking project. Review the upstream repository and source attribution before redistribution.

## 1Hosts Lite

- Project: https://github.com/badmojr/1Hosts
- Download format: hosts
- Primary endpoint: https://badmojr.github.io/1Hosts/Lite/hosts.txt
- Additional HTTPS mirrors: https://raw.githubusercontent.com/badmojr/1Hosts/master/Lite/hosts.txt ; https://cdn.jsdelivr.net/gh/badmojr/1Hosts@master/Lite/hosts.txt
- The Lite variant is the compatibility-oriented list; AdSkip intentionally does not enable the more aggressive Xtra list by default.
- Review the upstream license and current disclaimer before redistribution.

## Dan Pollock Hosts

- Project: https://someonewhocares.org/hosts/zero/
- Download format: hosts
- Official endpoint: https://someonewhocares.org/hosts/zero/hosts
- This source is fetched only from the maintainer's official host. The maintainer's notice permits non-commercial copying with attribution; preserve the original source attribution and do not redistribute contrary to its terms.

## AdSkip processing

- Only explicit domain records in supported dnsmasq and hosts formats are imported.
- Adblock exception rules, regular expressions, IP rules and unsupported directives are ignored rather than converted into block rules.
- Imported feeds are validated, bounded to 12 MiB / 120,000 domains, and stored separately from manual rules.
- Users may enable, disable, update or remove each supported feed independently.
- Downloads first try each configured HTTPS mirror, then use up to three bounded retries with 1 / 2.5 / 5 second backoff for transient failures.
- Each mirror has a 12-second attempt budget (5-second connect / 7-second read); the entire update remains capped at 90 seconds.
- Every request uses the current Android default Network; the next mirror/retry re-reads the active network after Wi-Fi/mobile handover. AdSkip does not silently bypass a user VPN.
- Failure diagnostics show only network type and internet validation state (VPN / Wi-Fi / cellular); no SSID or IP address is collected.
- The downloader enforces connection/read/overall timeouts, a 12 MiB streaming limit, HTTPS-only redirects and an allowlist of source mirror hosts.
- A failed, incomplete or untrusted download never replaces the previously imported copy.

These feeds are optional third-party data sources. They are not controlled or guaranteed by AdSkip. A blocklist may block legitimate services or miss unwanted domains. Use the lighter list first and keep manual rule backups before enabling broader coverage.

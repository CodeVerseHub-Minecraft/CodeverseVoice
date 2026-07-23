# CodeverseVoice

Voice chat moderation for Minecraft networks, built on Simple Voice Chat.

Restrictions follow the person rather than the account, staff moderate from a
menu or from commands anywhere on the network, and a lobby can display someone's
voice status without hosting voice at all.

Built for **Paper 26.2**, **Velocity 4** and **Java 25**.

## Why this exists

Simple Voice Chat is excellent at carrying audio and deliberately thin on
moderation. It has no restriction system, no evidence trail, no way to hear what
a reported player is hearing, and no notion of who someone is beyond the account
they logged in with. On a network that accepts Java, Bedrock and offline
accounts, that last gap matters: a restriction on one account is dodged by
switching to another.

## Modules

| Module | Runs on | Provides |
|---|---|---|
| `common` | neither | identity model, restrictions, config, messages, storage, cross server sync |
| `paper` | every backend | enforcement, menus, placeholders, notifications, commands |
| `velocity` | the proxy | network wide restrict, lift and lookup commands |

`common` depends on neither platform, which is what lets both modules share it
without either dragging the other's API onto the classpath.

## Two ways to run the Paper module

**On the server that hosts voice**, with Simple Voice Chat installed, the full
moderation layer is active: enforcement, monitoring, recording and the menus.

**On a lobby**, with Simple Voice Chat absent, the same jar runs in status only
mode. Placeholders, commands and notifications work; enforcement does not. This
is the intended arrangement rather than a fallback, and it is what lets a lobby
scoreboard show a restriction that was issued on the survival server.

Every reference to a Simple Voice Chat type lives behind one interface, so the
implementation is only loaded once the plugin is known to be installed. Without
that boundary the plugin fails to load at all on a lobby.

## Features

**Restrictions keyed to the person.** Bans are stored against the internal
identity written by the authentication plugin, so switching between linked Java
and Bedrock accounts does not shed a restriction. Without that table the plugin
falls back to per account keys and says so loudly at startup.

**Trust tier gating.** An account whose stored tier is not trusted is refused
before permissions are even consulted, so a mistyped LuckPerms group cannot hand
voice to an unverified account.

**Moderation menu.** `/voice gui <player>` opens a menu with the player's tier
and status, one click restriction lengths, agreed reason presets, restriction
history, monitoring and capture. Every action is also a command.

**Staff monitoring.** Hear what a reported player hears. Sessions end by
themselves, can be extended a limited number of times, announce themselves to
other staff, and are written to an audit trail on start and stop.

**Evidence capture.** A rolling in memory buffer of recent speech, written to
mp3 only when staff explicitly capture an incident, with an automatic deletion
date attached.

**Cross server propagation.** Restrictions issued anywhere apply everywhere
immediately over Redis.

## Placeholders

Requires PlaceholderAPI on the server displaying them.

| Placeholder | Output |
|---|---|
| `%codeversevoice_muted%` | styled Restricted or Active |
| `%codeversevoice_is_muted%` | `true` or `false` |
| `%codeversevoice_remaining%` | `1h30m`, `permanent`, or the none label |
| `%codeversevoice_remaining_seconds%` | seconds left, `0` when unrestricted |
| `%codeversevoice_reason%` | restriction reason |
| `%codeversevoice_tier%` | stored trust tier |
| `%codeversevoice_can_speak%` | `true` or `false`, including tier and permission |

All of them read from an in memory cache. Placeholders resolve on the main
thread, often once per tick per player on a scoreboard, so a database read there
would stall the server.

## Commands

Same command on both platforms; the proxy offers the subset that makes sense
away from the audio.

| Command | Paper | Velocity |
|---|---|---|
| `/voice gui <player>` | yes | no |
| `/voice ban <player> <duration> <reason>` | yes | yes |
| `/voice unban <player>` | yes | yes |
| `/voice check <player>` | yes | yes |
| `/voice history <player>` | yes | no |
| `/voice presets <player>` | yes | no |
| `/voice monitor <player\|stop\|extend>` | yes | no |
| `/voice capture <player> [note]` | yes | no |
| `/voice status` | yes | no |

Durations accept `30s`, `15m`, `2h`, `7d`, `2w`, compound forms like `1d12h`,
and `perm`. A bare number is rejected rather than guessed at.

## Configuration

Everything lives in `config.json`, written with defaults on first start and
merged forward on upgrade. Both modules read the same file, so one config can be
copied between servers without editing.

Notable sections:

- `access` trust tiers, speak permission, denial notice style
- `notifications` thirteen independent switches for who is told what, plus sounds
- `gui` rows, quick durations, fill material, confirmations, click sounds
- `presets` named reasons with attached durations and icons
- `placeholders` label text for every placeholder output
- `recording` buffer length, retention, disk budget
- `monitoring` session length, extension limit, staff announcements
- `ranges` per permission proximity distances
- `groups` name rules and permission gating

Validation refuses to start on values that would quietly break something,
including a recording buffer long enough to become an archive.

## Data protection

Recorded speech is personal data under the GDPR and the Swiss FADP, so the
design assumes that rather than treating it as an afterthought.

Nothing reaches disk without an explicit capture. The rolling buffer is memory
only and discards continuously. Every capture carries a deletion date enforced
by a sweep, not by whoever remembers. Players are told on connect that a buffer
exists. Configuration refuses a buffer longer than five minutes.

If you disable `recording.retentionDays`, have a written retention policy first.

## Requirements

- Paper 26.2 or newer, or Velocity 4, on Java 25
- MySQL or MariaDB, shared with the other Codeverse plugins
- Simple Voice Chat, on the server that hosts voice
- LuckPerms and PlaceholderAPI are optional but expected in practice
- Redis is optional; without it, each server's cache expires on its own

## Building

```bash
./gradlew build
```

Produces `paper/build/libs/CodeverseVoice-Paper-<version>.jar` and
`velocity/build/libs/CodeverseVoice-Velocity-<version>.jar`.

Note for contributors: shadow's `minimize()` must stay disabled. Caffeine and
Lettuce resolve classes reflectively, and minimization strips classes that are
needed at runtime but invisible to static analysis.

## Testing

```bash
./gradlew test
```

Covers duration parsing, restriction expiry, the ring buffer's forgetting
behaviour, configuration validation, the access decision ordering, and every
bundled message rendering as valid MiniMessage.

Enforcement, capture and monitoring are exercised through their own logic but
have not been driven by a real voice client; those paths are best confirmed on
your own server before relying on them.

## License

MIT. See [LICENSE](LICENSE) and [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## About

Built and maintained by the **CodeVerseHub-Minecraft Subteam**.

We work alongside the wider CodeVerseHub community but operate as a separate
team; CodeVerseHub is not responsible for this project.

# Third-Party Notices

CodeverseVoice itself is MIT licensed. The distributed jars are shaded and
bundle the libraries below, so distributing them means redistributing those
libraries. Their licenses apply to those portions.

## Bundled dependencies

| Library | Version | License |
|---|---|---|
| HikariCP | 7.1.0 | Apache License 2.0 |
| Caffeine | 3.2.4 | Apache License 2.0 |
| Lettuce | 7.6.0.RELEASE | Apache License 2.0 |
| Netty (via Lettuce) | 4.2.x | Apache License 2.0 |
| Project Reactor (via Lettuce) | 3.6.x | Apache License 2.0 |
| Reactive Streams | 1.0.4 | MIT-0 |
| Gson | 2.11.0 | Apache License 2.0 |
| SLF4J API | 2.0.17 | MIT License |
| CodeverseAPI (`api`) | 0.2.0 | MIT License |
| CodeverseAPI (`jdbc`) | 0.2.0 | MIT License |

The two CodeverseAPI artifacts are bundled only in the Paper jar and
deliberately not relocated. A backend has no proxy to provide the shared
contract, so it ships the jdbc identity implementation, which brings the api
interfaces with it. They stay at their canonical package so that any other
backend plugin resolves the same classes.

The Velocity jar excludes both. On the proxy the interfaces are provided at
runtime by CodeverseAuth, and shipping a second copy would give this plugin a
different provider from the one CodeverseAuth registered into, so its voice
service would register where nothing could find it.

No JDBC driver is bundled. The Paper and Velocity platforms both provide one,
and shipping another would mean including a GPL component in an MIT project for
no benefit. Set `storage.driverClassName` to whichever driver your server has.

## Compile only, not redistributed

Paper API, Velocity API, Simple Voice Chat API, LuckPerms API and
PlaceholderAPI are compile time dependencies. None are included in the jars.

## Apache License 2.0 attribution

Several bundled libraries are Apache 2.0, which requires attribution notices to
be preserved. The shaded jars keep `META-INF/LICENSE` and `META-INF/NOTICE`
entries for this reason. If you adjust the shading configuration, do not
exclude those paths.

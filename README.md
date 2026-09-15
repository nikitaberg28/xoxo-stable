# xoxo-AntiCheat stable ver.

This anti-cheat was forked for a Russian-speaking server targeting the Bedrock audience, so many checks here are either disabled or relaxed. Additionally, a "NoWall" check was added to detect hits through walls

| Subcommand                         | Description                                                                            |
|------------------------------------|----------------------------------------------------------------------------------------|
| `/xoxo check`    | Open the menu showing potential cheaters         |
| `/xoxo reload` | Reload config |
| `/xoxo alert`             | Enable/disable alerts. They are disabled by default in the console                                    |
| `/xoxo unvanish`        | Disabling invisibility from the /xoxo check menu                                            |

## Planned Features

- [ ] Support for Version under 1.20 and 26.1, 26.1.2 (im not sure im going to do that)
- [x] Expanded `/xoxo` command (view flags, manage bans, reload config)
- [x] Per-check enable/disable in config
- [X] Flag logging to file / database
- [x] Rubberband misfire fix for edge-case jumps
- [x] Fix for leaf/plant blocks in water false positives
- [X] Fix reach check for the ender dragon

## License
[MIT](LICENSE) — free to use, modify, and distribute.
*xoxo-AntiCheat — because your players deserve a fair server.*
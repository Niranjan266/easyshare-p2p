# EasyShare — Peer-to-Peer File Sharing System (Java)

**Website and user manual:** https://easyshare-p2p.vercel.app

A decentralized file distribution system based on BitTorrent mechanisms, written in plain Java
(JDK 17+, no external libraries).

- **File chunking**: files are split into pieces (default 256 KB)
- **SHA-1 piece validation**: every received piece is hashed; corrupted pieces are rejected and re-downloaded
- **Multithreaded socket streams**: a thread pool serves uploads; one worker thread per peer downloads in parallel
- **Peer discovery**: tracker (TCP), LAN multicast (UDP) and manual `connect ip:port`
- **Swarming**: peers share pieces while still downloading; rarest-first piece selection
- **Pause / resume** across restarts, `.p2pmeta` metadata files (like `.torrent`)
- **Console UI** and a **web dashboard** served by the Java program itself
- **Phone page**: phones on the same Wi-Fi download/upload files in the browser (`phone.bat`)

## EasyShare Messenger (IP Messenger style)

Double-click **`messenger.bat`** on every PC in the same network. Other PCs appear automatically;
select one, type a message, attach files/folders (or drag them in) and click **Send**. The receiver
gets a popup with **Save files / Decline / Reply**; files are transferred in SHA-1 verified pieces.

## Quick start (Windows)

```bat
build.bat        :: compile -> EasyShare.jar
demo.bat         :: tracker + Alice + Bob + Carol on one computer
```

In the **Carol** window:

```
search
get 3
```

Carol downloads `sample-video.bin` from Alice and Bob in parallel. Bob deliberately corrupts 15% of
the pieces he sends, and Carol detects them with SHA-1 and downloads them again. Carol's web
dashboard is at http://localhost:8003/.

| Script | Purpose |
|---|---|
| `build.bat` | Compile sources into `EasyShare.jar` |
| `demo.bat` / `demo-dave.bat` | One-computer demo (3 or 4 peers) |
| `clean-demo.bat` | Delete demo folders |
| `start-tracker.bat` | Start a tracker (port 7000) |
| `start-peer.bat` | Start a peer (asks for name, port, tracker) |
| `messenger.bat` | IP Messenger-style app: auto-discover PCs, send messages, files and folders |
| `messenger-second.bat` | Second messenger window on the same PC for testing |
| `phone.bat` | Share files with a phone browser on the same Wi-Fi (download + upload) |
| `run-tests.bat` | Automated end-to-end tests |

## Command line

```
java -jar EasyShare.jar tracker [--port 7000]
java -jar EasyShare.jar peer --name Alice --port 6001 --shared shared --downloads downloads --tracker 192.168.1.10:7000 --web 8001
java -jar EasyShare.jar selftest
```

## Documentation

See **[USER_MANUAL.md](USER_MANUAL.md)**: setup on several computers, all commands, demonstration
scenarios for the viva, protocol reference, troubleshooting and viva questions.

## Source layout

```
src/easyshare/
  Main.java, SelfTest.java
  common/   Protocol, HashUtil (SHA-1), RateLimiter, NetUtil, Format, PeerAddress
  meta/     FileMeta (chunking + piece hashes + info hash)
  tracker/  TrackerServer, TrackerClient, TrackerCli
  peer/     PeerNode, PeerServer, PeerClient, Download, PieceManager, SharedFile, FileRegistry, LanDiscovery, PeerCli
  web/      WebDashboard (Java HttpServer) + dashboard.html
website/    Static project website (Vercel)
```

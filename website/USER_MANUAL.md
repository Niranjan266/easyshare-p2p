# EasyShare — Peer-to-Peer File Sharing System

### User Manual

**Project 07 · Distributed Systems · Language: Java (JDK 17+) · No external libraries**

A decentralized file distribution system based on BitTorrent mechanisms. Files are split into
pieces (chunks), pieces are exchanged directly between connected peers over TCP, and every piece is
validated with a SHA-1 hash.

---

## Contents

1. [Introduction](#1-introduction)
2. [How EasyShare works](#2-how-easyshare-works)
3. [System requirements](#3-system-requirements)
4. [Project files](#4-project-files)
5. [Installation (build)](#5-installation-build)
6. [Quick start: demo on one computer](#6-quick-start-demo-on-one-computer)
7. [Using EasyShare on several computers (LAN)](#7-using-easyshare-on-several-computers-lan)
8. [Console commands](#8-console-commands)
9. [Web dashboard](#9-web-dashboard) (includes sharing with a phone)
10. [Command-line options](#10-command-line-options)
11. [Demonstration scenarios (for the viva)](#11-demonstration-scenarios-for-the-viva)
12. [Automated tests](#12-automated-tests)
13. [Protocol reference](#13-protocol-reference)
14. [Source code overview](#14-source-code-overview)
15. [Troubleshooting](#15-troubleshooting)
16. [Viva questions and answers](#16-viva-questions-and-answers)
17. [Limitations and future work](#17-limitations-and-future-work)

---

## 1. Introduction

Traditional file sharing sends every file through a central server. If the server is slow or
offline, nobody can download, and all traffic flows through it.

**EasyShare** removes the central file server. Every user runs a **peer** that both **uploads** and
**downloads**. A file is cut into fixed-size **pieces**; a downloader fetches different pieces from
different peers **at the same time** and checks each piece with **SHA-1** before saving it. As soon
as a peer has a piece, it can pass that piece on to others — just like BitTorrent.

### Features

| Feature | Description |
|---|---|
| File chunking | Files are split into pieces (default 256 KB). |
| SHA-1 piece validation | Each piece is hashed on arrival; corrupted pieces are rejected and downloaded again. |
| Whole-file verification | After the last piece, the SHA-1 of the whole file is checked too. |
| Multi-source download | One thread per peer; pieces arrive from several peers in parallel. |
| Rarest-first piece selection | Rare pieces are requested first so they spread quickly (BitTorrent strategy). |
| Swarming | Peers share pieces while they are still downloading; finished downloads are seeded automatically. |
| Peer discovery — tracker | A small tracker service tells peers who has which file. It stores **no file data**. |
| Peer discovery — LAN | UDP multicast finds peers on the local network without any tracker. |
| Manual connect | Connect directly to `IP:port` of a peer. |
| Search | Search file names across the network. |
| Pause / resume | Downloads can be paused, the program closed, and the download continued later. |
| Corrupt-peer blocking | A peer that sends 5 corrupted pieces is blocked for that download. |
| Metadata files | Export a `.p2pmeta` file (like a `.torrent`) and open it on another computer. |
| Web dashboard | Optional browser interface served by the Java program itself. |
| Demo helpers | Upload speed limit and a *corruption simulator* to demonstrate integrity checking. |

---

## 2. How EasyShare works

### 2.1 Architecture

```
                         +--------------------------+
                         |         TRACKER          |   who has which file?
                         |  (peer discovery only)   |   (never stores file data)
                         +-----+-----------+--------+
          register /           |           |          get peers for
          announce files       |           |          info hash
                               |           |
   +---------------+     +-----+-----+     +-----+---------+
   |    PEER A     |     |   PEER B  |     |    PEER C     |
   |  (seeder)     |     |  (seeder) |     | (downloader)  |
   | shared files  |     |           |     |               |
   +-------+-------+     +-----+-----+     +---+-------+---+
           |                   |               |       |
           |    pieces 0,3,4.. |  TCP          |       |
           +-------------------+---------------+       |
                               |  pieces 1,2,5..       |
                               +-----------------------+
                  Peers exchange pieces DIRECTLY with each other
```

### 2.2 Key terms

| Term | Meaning |
|---|---|
| **Peer** | A running copy of EasyShare. It has a TCP server (uploads) and download threads. |
| **Piece / chunk** | A fixed-size part of a file (default 256 KB; the last piece may be smaller). |
| **Piece hash** | SHA-1 of one piece, stored in the metadata. |
| **Metadata (`.p2pmeta`)** | File name, size, piece size, whole-file SHA-1 and the list of piece hashes. |
| **Info hash** | SHA-1 of the metadata text. It is the unique ID of a file on the network. |
| **Bitfield** | A list of bits saying which pieces a peer has (1 = has, 0 = missing). |
| **Seeder** | A peer that has 100% of a file. |
| **Swarm** | All peers sharing the same file. |
| **Tracker** | Service that maps info hash → list of peers. |

### 2.3 What happens when you download a file

1. **Share.** When a peer starts, it reads every file in its shared folder, splits it into pieces
   and computes the SHA-1 of each piece and of the whole file. From this it builds the metadata
   and the **info hash**.
2. **Announce.** The peer tells the tracker "I am Alice at 192.168.1.10:6001 and I have file
   `<info hash>` (100%)". This heartbeat repeats every 15 seconds.
3. **Search.** The downloader asks the tracker (and any directly known peers) for matching files.
4. **Get metadata.** The downloader asks a peer for the metadata and checks that its SHA-1 equals
   the info hash. A fake piece list is therefore impossible.
5. **Find peers.** It asks the tracker which peers have this info hash.
6. **Connect.** One worker thread per peer opens a TCP connection and performs a **handshake**; the
   peer replies with its **bitfield**.
7. **Pick a piece.** Each worker asks the shared *PieceManager* for the rarest missing piece that its
   peer has. A piece is never requested from two peers at the same time.
8. **Download and verify.** The worker requests the piece, computes its SHA-1 and compares it with
   the metadata:
   - match → write it to `name.xxxx.part` at the correct offset and mark it done;
   - mismatch → discard it and put it back in the queue (another peer may supply it).
9. **Share while downloading.** Pieces already received are immediately available to other peers.
10. **Finish.** When all pieces are present, the whole-file SHA-1 is checked, the `.part` file is
    renamed to the real name and the peer becomes a **seeder**.

### 2.4 Resume

The metadata of every download is kept in `downloads\.easyshare\`. When a peer starts again, it
re-hashes the pieces in each `.part` file; valid pieces are kept, so `resume` continues from where
it stopped — even after the computer was restarted.

---

## 3. System requirements

| Item | Requirement |
|---|---|
| Operating system | Windows 10/11 (the `.bat` scripts). Linux/macOS work with the `java` commands. |
| Java | **JDK 17 or newer** (tested with JDK 26). Check with `java -version` and `javac -version`. |
| Network | Any LAN / Wi-Fi. A single computer is enough for the demo. |
| Ports | Tracker TCP 7000, peers TCP 6001–6004, dashboards TCP 8001–8004, LAN discovery UDP 45454. |
| Libraries | None — only the Java standard library. |

---

## 4. Project files

```
EasyShare\
├── build.bat            Compile the sources into EasyShare.jar
├── demo.bat             One-click demo: tracker + Alice + Bob + Carol in separate windows
├── demo-dave.bat        Add a 4th peer to the demo
├── clean-demo.bat       Delete demo folders and downloads
├── start-tracker.bat    Start a tracker
├── start-peer.bat       Start a peer (asks for name, port, tracker)
├── phone.bat            Share files with a phone browser over Wi-Fi
├── run-tests.bat        Run the automated end-to-end tests
├── USER_MANUAL.md       This manual
├── README.md            Short project description
├── website\             Static project website (deployable to Vercel)
└── src\easyshare\
    ├── Main.java            Entry point (tracker | peer | makefile | selftest)
    ├── SelfTest.java        Automated tests
    ├── common\              Protocol, hashing, rate limiter, formatting, networking helpers
    ├── meta\FileMeta.java   Chunking + SHA-1 metadata (".torrent" equivalent)
    ├── tracker\             Tracker server, tracker client, tracker console
    ├── peer\                Peer node, upload server, download engine, LAN discovery, console
    └── web\                 Web dashboard (Java HttpServer + dashboard.html)
```

Folders created while running:

| Folder | Content |
|---|---|
| `demo\alice\shared` etc. | Demo peers' shared files |
| `demo\carol\downloads` | Carol's downloaded files |
| `downloads\*.part` | Unfinished downloads |
| `downloads\.easyshare\` | Saved metadata for resuming (do not delete during a download) |

---

## 5. Installation (build)

1. Install a JDK 17+ (e.g. Oracle JDK or Eclipse Temurin).
2. Open the `EasyShare` folder and **double-click `build.bat`** (or run it in Command Prompt).
3. You should see:

```
Compiling Java sources...
Creating EasyShare.jar...

BUILD SUCCESSFUL: C:\...\EasyShare\EasyShare.jar
```

All other `.bat` files build the project automatically if `EasyShare.jar` does not exist.

> If you see *"the 'jar' tool was not found"*, set `JAVA_HOME` to your JDK folder, e.g.
> `setx JAVA_HOME "C:\Program Files\Java\jdk-26"`, and open a new Command Prompt.

---

## 6. Quick start: demo on one computer

### Step 1 — Start the demo

Double-click **`demo.bat`**. It creates sample files and opens four windows:

| Window | Role | Port | Dashboard |
|---|---|---|---|
| EasyShare Tracker | Peer discovery | 7000 | – |
| Alice (seeder) | Shares `sample-video.bin` (12 MB) and `notes.txt` | 6001 | http://localhost:8001 |
| Bob (seeder) | Shares `sample-video.bin` and `dataset.bin`; **corrupts 15% of pieces he sends** | 6002 | http://localhost:8002 |
| Carol (downloader) | Empty; downloads files | 6003 | http://localhost:8003 |

Uploads are limited to 300 KB/s per peer so you can watch the transfer. Carol's dashboard opens in
your browser.

> If Windows Firewall asks whether Java may communicate on networks, click **Allow**.

### Step 2 — Look at the network (Carol window)

```
Carol> peers
Peers registered at tracker 127.0.0.1:7000: 3
  Alice            127.0.0.1:6001         2 file(s)
  Bob              127.0.0.1:6002         2 file(s)
  Carol            127.0.0.1:6003         0 file(s)  <- you
```

### Step 3 — Search

```
Carol> search
Search results:
  #   Name                           Size        Peers  Seeders  Info hash
  1   dataset.bin                    3.0 MB      1      1        28cbc73d96
  2   notes.txt                      229 B       1      1        5b1e0c7a41
  3   sample-video.bin               12.0 MB     2      2        a65bac7a34
Type 'get <#>' to download.
```

### Step 4 — Download

```
Carol> get 3
Requesting metadata (piece list) from peers...
Downloading sample-video.bin (12.0 MB, 48 pieces of 256.0 KB)
Press ENTER to pause.

  ! Piece #31 from Bob (127.0.0.1:6002) failed SHA-1 check -> discarded, will download again
  [##############----------------]  47% 23/48 pieces  589.4 KB/s  peers: 2  bad pieces: 1
```

When finished:

```
DOWNLOAD COMPLETE: C:\...\EasyShare\demo\carol\downloads\sample-video.bin
  Time: 21.3 s   Average speed: 576.9 KB/s
  Pieces received from each peer:
    Alice (127.0.0.1:6001)               25 pieces
    Bob (127.0.0.1:6002)                 23 pieces
  Corrupted pieces detected and re-downloaded: 3
  Every piece passed its SHA-1 check.
  Whole-file SHA-1: 9eae94c307239d8df7e2e0c02f94bfb0526fe73d  -> Integrity: VERIFIED
  You are now seeding this file to other peers.
```

(Your numbers will differ slightly each run.)

Meanwhile the **Alice** and **Bob** windows show uploads, and Bob shows the simulated corruption:

```
[19:04:11] [UPLOAD] Carol (127.0.0.1) connected to download sample-video.bin (we have 48/48 pieces)
[19:04:15] [SIMULATION] Sending CORRUPTED piece #31 of sample-video.bin to Carol (127.0.0.1)
[19:04:32] [UPLOAD] Sent 23 pieces (5.6 MB) of sample-video.bin to Carol (127.0.0.1)
```

### Step 5 — Stop / reset

Type `quit` in each peer window (or close the windows). Run **`clean-demo.bat`** to delete the demo
folders and start again from scratch.

---

## 7. Using EasyShare on several computers (LAN)

All computers must be on the same network (same Wi-Fi / switch).

### Step 1 — Choose the tracker computer and find its IP

On the tracker computer open Command Prompt and run `ipconfig`. Note the **IPv4 Address** of the
Wi-Fi/Ethernet adapter, e.g. `192.168.1.10`. (The tracker window also prints its LAN IPs.)

### Step 2 — Start the tracker

Double-click **`start-tracker.bat`**. Allow Java through the firewall when asked.

### Step 3 — Start a peer on every computer

Double-click **`start-peer.bat`** and answer the questions:

```
Your peer name        [niran]: Alice
TCP port              [6001]:
Tracker IP:port (or none) [127.0.0.1:7000]: 192.168.1.10:7000
Web dashboard port (or none) [8001]:
```

Put the files you want to share into `EasyShare\peers\<name>\shared` and type `refresh`.

> **Important:** use the tracker's LAN IP (e.g. `192.168.1.10:7000`) on *every* computer, including
> the tracker computer itself if a peer also runs there.

Or start with all options directly:

```bat
start-peer.bat --name Alice --port 6001 --shared D:\share --downloads D:\downloads --tracker 192.168.1.10:7000 --web 8001
```

### Step 4 — Search and download

On any peer: `search`, then `get <#>` — exactly as in the one-computer demo.

### Without a tracker

Peers can find each other without a tracker:

- **LAN discovery:** type `discover` — peers on the same network answer via UDP multicast.
- **Direct connection:** type `connect 192.168.1.20:6001` to add a peer by IP and port.

Then use `search` and `get` as usual.

### Firewall

If peers cannot reach each other, allow Java in *Windows Defender Firewall → Allow an app through
firewall* for **Private** networks, or open TCP ports 7000, 6001–6010 and UDP 45454.

---

## 8. Console commands

Type these at the `Name>` prompt of a peer.

### My files

| Command | Description |
|---|---|
| `myfiles` | List shared files and unfinished downloads (size, pieces, status, info hash). |
| `info <#>` | Show chunk details: piece size, piece count, info hash, file SHA-1, piece map, first piece hashes. |
| `verify <#>` | Re-hash every piece on disk and the whole file; reports corrupted pieces. |
| `refresh` | Rescan the shared folder after adding/removing files. |
| `export <#>` | Save `name.p2pmeta` (torrent-like metadata) in the EasyShare folder. |

### Network

| Command | Description |
|---|---|
| `peers` | Peers registered at the tracker, plus peers added by `connect` / `discover`. |
| `discover` | Find peers on the local network via UDP multicast (no tracker needed). |
| `connect <ip:port>` | Connect to a peer directly and list its files. |
| `search [keyword]` | Search files on the network; no keyword lists everything. |

### Download

| Command | Description |
|---|---|
| `get <#>` | Download result number `#` of the last `search` / `connect`. |
| `get <info hash>` | Download by the 40-character info hash. |
| `open <file.p2pmeta>` | Download the file described by a metadata file. |
| **ENTER** (during a download) | Pause the download. |
| `resume [#]` | Continue an unfinished download (number from `downloads`). |
| `downloads` | Show unfinished and completed downloads. |
| `stats` | Total bytes uploaded and downloaded. |

### Other

| Command | Description |
|---|---|
| `help` | List all commands. |
| `quit` | Unregister from the tracker and exit. |

### Tracker console

The tracker window accepts: `peers` (online peers), `files` (files and how many peers/seeders have
them), `quit`.

---

## 9. Web dashboard

Start a peer with `--web <port>` (the demo uses 8001–8003) and open `http://localhost:<port>/`.
The dashboard is served by the same Java program and only accepts connections from the same computer.

| Area | What you can do |
|---|---|
| **Header** | Peer name, port, tracker status, total uploaded/downloaded, piece size, simulation flags. |
| **My files** | Shared files with a **piece grid** (one square per chunk; blue = have). **Verify SHA-1** re-checks a file. **Rescan shared folder** = `refresh`. |
| **Downloads** | Live progress bar, pieces done, speed, connected peers, corrupted pieces rejected, **pieces received from each peer**, event log, **Pause / Resume** buttons. |
| **Find files** | Search the network, or connect to `IP:port` directly; **Download** buttons. |
| **Peers** | Peers from the tracker and direct/LAN peers; **LAN discover** button. |

The console and the dashboard can be used at the same time.

### 9.1 Sharing files with a phone (Android or iPhone)

Phones cannot run the Java peer, but a PC peer can show a **phone page** that works in any phone
browser — no app needed.

1. Connect the phone to the **same Wi-Fi** as the PC (or connect the PC to the phone's **hotspot**).
2. On the PC double-click **`phone.bat`** (or start any peer with `--phone 9001`).
3. Allow Java through Windows Firewall (**Private networks**) if asked.
4. The window prints the address to open, labelled with the network adapter:
   ```
   Phone page    : on your phone (same Wi-Fi) open the address of your Wi-Fi adapter:
                   http://192.168.31.56:9001/  (Realtek ... WiFi 6 Adapter)
                   http://192.168.56.1:9001/  (VirtualBox Host-Only Ethernet Adapter)
   ```
   Type the **Wi-Fi** address (the first line) into the phone's browser.
5. **PC → phone:** files in `phone-share\shared` are listed; tap **Download**.
   Add more files to that folder and type `refresh` in the PC window.
6. **Phone → PC:** under *Send a file from this phone*, choose a file (photo, PDF…) and tap
   **Upload**. It is saved in the shared folder, hashed into pieces and shared with all peers.

The PC window logs every transfer, e.g. `[PHONE] Received IMG_2041.jpg (2.3 MB) from 192.168.31.80`.

> The phone page is visible to everyone on the same network and has no password — use it on your
> own Wi-Fi or hotspot. The phone uses normal HTTP downloads; it is not itself a P2P peer.
> (Android users can run a full peer with the Termux app and OpenJDK.)

---

## 10. Command-line options

```
java -jar EasyShare.jar tracker [--port 7000]
java -jar EasyShare.jar peer [options]
java -jar EasyShare.jar makefile <path> <size in MB>
java -jar EasyShare.jar selftest
```

| Peer option | Default | Description |
|---|---|---|
| `--name <name>` | Peer | Display name. |
| `--port <port>` | 6001 | TCP port other peers connect to. Each peer on one computer needs a different port. |
| `--shared <folder>` | shared | Folder whose files are shared. |
| `--downloads <folder>` | downloads | Where downloads are saved (must differ from `--shared`). |
| `--tracker <ip:port>` | none | Tracker for peer discovery. |
| `--web <port>` | off | Start the web dashboard on this port. |
| `--phone <port>` | off | Start the phone page (download/upload from phone browsers on the same Wi-Fi). |
| `--piece <KB>` | 256 | Piece size in KB (1–8192). Peers sharing the *same* file must use the same piece size to form one swarm. |
| `--limit <KB/s>` | unlimited | Upload speed limit (to make transfers visible in demos). |
| `--corrupt <percent>` | 0 | **Simulation:** randomly corrupt this % of uploaded pieces. |
| `--host <ip>` | auto | IP address announced to the tracker (use if the computer has several networks). |
| `--no-lan` | – | Disable LAN multicast discovery. |

---

## 11. Demonstration scenarios (for the viva)

Start with `demo.bat`. Each scenario lists what to do and what it proves.

### A. Chunking and hashing

In the **Alice** window: `myfiles`, then `info 2` (sample-video.bin).
**Shows:** a 12 MB file is 48 pieces of 256 KB, each with its own SHA-1; the info hash identifies the file.

### B. Multi-source (parallel) download

In **Carol**: `search`, `get 3`.
**Shows:** "Pieces received from each peer" lists both Alice and Bob — pieces came from two peers in
parallel (one thread per peer). Carol's dashboard shows two per-peer bars growing at the same time.

### C. Integrity check with SHA-1

Bob runs with `--corrupt 15`. During scenario B, Carol prints lines like
`Piece #31 from Bob ... failed SHA-1 check -> discarded, will download again`, and Bob's window
prints `[SIMULATION] Sending CORRUPTED piece`.
**Shows:** corrupted data is detected per piece and never written; the final file is still `VERIFIED`.

### D. Pause and resume (even after closing the program)

1. `clean-demo.bat`, then `demo.bat` again.
2. In Carol: `search`, `get 3`, press **ENTER** at about 40% → `PAUSED`.
3. Close Carol's window completely. Start Carol again:
   `java -jar EasyShare.jar peer --name Carol --port 6003 --shared demo\carol\shared --downloads demo\carol\downloads --tracker 127.0.0.1:7000 --web 8003`
4. The start-up log says `Unfinished download sample-video.bin: 19/48 pieces verified on disk`.
5. Type `resume`. The summary shows `(already on disk - resumed) 19 pieces`.

**Shows:** resumable transfers; already verified pieces are not downloaded again.

### E. Swarm — downloading from another downloader

1. Start the demo, and in Carol type `get 3`.
2. While Carol is downloading, run **`demo-dave.bat`**, and in Dave: `search`, `get 3`.
3. Dave's summary lists pieces from Alice, Bob **and Carol**.

To prove it more strongly, after Carol has finished close Alice and Bob, then let Dave download:
all pieces come from Carol, who was only a downloader at the beginning.

### F. Peer discovery without a tracker

Close the tracker window. In Carol: `discover` → Alice and Bob are found on the LAN.
Then `search` and `get` still work. You can also use `connect 127.0.0.1:6001`.
**Shows:** two discovery mechanisms (tracker, LAN multicast) plus manual connection.

### G. Metadata files (`.p2pmeta`)

In Alice: `export 2` → creates `sample-video.bin.p2pmeta` in the EasyShare folder. In a peer that does
not have the file yet (e.g. Dave from `demo-dave.bat`): `open sample-video.bin.p2pmeta`.
**Shows:** a file can be shared by giving someone the small metadata file, like a `.torrent`.

### H. Detecting a damaged file on disk

1. In Alice type `verify 1` (notes.txt) → `Integrity: VERIFIED`.
2. Open `demo\alice\shared\notes.txt` in Notepad, change one letter (keep the same length) and save.
3. In Alice type `verify 1` again → `piece #0 is CORRUPTED (hash mismatch)` and `Integrity: FAILED`.
4. Type `refresh`: Alice re-hashes the changed file. It now has a **new info hash** — for the network
   it is a different file, so nobody can receive the modified content believing it is the original.

---

## 12. Automated tests

Double-click **`run-tests.bat`** (or `java -jar EasyShare.jar selftest`). It starts a tracker and
seven peers inside one program on random ports and checks:

```
  [PASS] File is split into pieces (1 MB / 64 KB = 17 pieces)
  [PASS] Metadata survives serialize -> parse with the same info hash
  [PASS] Changing one piece hash changes the info hash (tamper-evident)
  [PASS] Path traversal file names in metadata are rejected
  [PASS] Tracker search finds movie.bin shared by 2 peers
  [PASS] Carol downloads movie.bin (3 MB, 49 pieces) from the swarm
  [PASS] Downloaded file SHA-1 equals the original
  [PASS] Pieces were received from both Alice and Bob in parallel
  [PASS] Corrupted pieces from Bob were detected by SHA-1 and re-downloaded
  [PASS] After Alice and Bob leave, Dave downloads the file from Carol (a former downloader)
  [PASS] After restart, the unfinished download is found and its pieces re-verified on disk
  [PASS] Resumed download completes without re-downloading finished pieces

Result: 12 passed, 0 failed
```

---

## 13. Protocol reference

### 13.1 Tracker protocol (TCP, text, one command per line)

| Command | Reply |
|---|---|
| `REGISTER name host port` | `OK` |
| `ANNOUNCE host port infoHash length percent name` | `OK` |
| `UNREGISTER host port` | `OK` |
| `PEERS` | `PEER name host port fileCount` … `END` |
| `GETPEERS infoHash` | `PEER name host port percent` … `END` |
| `SEARCH keyword` | `FILE infoHash length peers seeders name` … `END` |
| `QUIT` | connection closed |

Names are URL-encoded. `host` = `-` means "the address this connection comes from". Peers repeat
REGISTER + ANNOUNCE every 15 s; peers silent for 60 s are removed.

### 13.2 Peer-wire protocol (TCP, binary)

Connection start (both sides): `int MAGIC (0x45535031)`, `string peerId`, `string peerName`.

| Command (1 byte) | Arguments | Reply |
|---|---|---|
| `LIST` (1) | – | `int n` then n × (`string infoHash`, `string name`, `long size`, `int pieces`, `int havePieces`) |
| `META` (2) | `string infoHash` | `OK string metadata` or `ERROR string` |
| `HANDSHAKE` (3) | `string infoHash` | `OK bitfield` or `ERROR string` |
| `BITFIELD` (4) | – | `OK bitfield` (refresh) |
| `REQUEST` (5) | `int index` | `PIECE int index, int length, bytes` or `NO_PIECE int index` |
| `BYE` (6) | – | connection closed |

`string` = `int length` + UTF-8 bytes. `bitfield` = `int count` + ⌈count/8⌉ bytes (most significant bit first).

### 13.3 LAN discovery (UDP multicast 239.255.42.99:45454)

Request: `EASYSHARE-DISCOVER <peerId>` → Reply (unicast): `EASYSHARE-HERE <peerId> <tcpPort> <name>`.

### 13.4 Metadata format (`.p2pmeta`)

```
EASYSHARE-META/1
name=sample-video.bin
length=12582912
pieceLength=262144
fileHash=9eae94c307239d8df7e2e0c02f94bfb0526fe73d
pieces=48
7d3c25a5a2bbd304b4d6bb8f8acd998335e13aa3
...
```

Info hash = SHA-1 of this exact text.

---

## 14. Source code overview

| Proposal module | Classes |
|---|---|
| **1. Peer server** | `peer/PeerServer` — `ServerSocket`, thread pool, serves LIST/META/HANDSHAKE/REQUEST, upload rate limit, corruption simulator |
| **2. Peer client** | `peer/PeerClient` — TCP connection to one peer; `peer/Download` — coordinator + worker thread per peer |
| **3. File sharing manager** | `peer/FileRegistry`, `peer/SharedFile` — shared folder scan, `.part` files, safe file names, resume state |
| **4. File transfer** | `common/Protocol` (wire format), `peer/PieceManager` (rarest-first), `common/RateLimiter` |
| **5. Integrity verification** | `meta/FileMeta` (chunking, piece hashes, info hash), `common/HashUtil` (SHA-1), checks in `Download` and `SharedFile.finish()` |
| **Peer discovery** | `tracker/TrackerServer`, `tracker/TrackerClient`, `peer/LanDiscovery` |
| **User interface** | `peer/PeerCli` (console), `web/WebDashboard` + `dashboard.html` (browser), `tracker/TrackerCli` |
| **Core** | `peer/PeerNode` — ties everything together; `Main`; `SelfTest` |

**Concurrency used:** `ExecutorService` thread pools (uploads, download workers, tracker clients),
`ScheduledExecutorService` (heartbeat, tracker clean-up), `ConcurrentHashMap`, `AtomicInteger/AtomicLong`,
`synchronized` piece selection and file I/O.

---

## 15. Troubleshooting

| Problem | Solution |
|---|---|
| `javac` / `java` not recognized | Install JDK 17+ and add its `bin` folder to PATH; open a new Command Prompt. |
| `the 'jar' tool was not found` | Set `JAVA_HOME` to the JDK folder (see section 5). |
| `port 6001 is already in use` | Another peer uses that port — start with `--port 6005`, or close the old window. |
| `Tracker ... is not reachable` | Tracker not started, wrong IP, or firewall. Peers keep retrying; `discover`/`connect` still work. |
| `search` finds nothing | Wait a few seconds after starting peers; check `peers`; make sure the seeders ran `refresh` after adding files. |
| Other computers cannot connect | Use the LAN IP (not 127.0.0.1) in `--tracker`; allow Java in the firewall; same network. If a PC has several adapters (VirtualBox, VPN), start with `--host <real LAN IP>`. |
| `discover` finds no peers | Multicast may be blocked by the router/firewall or a VPN adapter; use the tracker or `connect`. |
| Download stays at the same % | No online peer has the missing pieces. It pauses automatically after ~25 s; `resume` later. |
| Same file appears twice in search | The two copies differ (different content or different `--piece` size) → different info hash. |
| Phone cannot open the phone page | Same Wi-Fi? Use the IP next to the **Wi-Fi** adapter, include `:9001`, type `http://` (not https), allow Java in the firewall, avoid guest/college Wi-Fi (use a hotspot). |
| Dashboard page does not open | Start the peer with `--web 8001`; open `http://localhost:8001/` on the **same** computer. |
| Want to start fresh | Close all windows and run `clean-demo.bat`. |

---

## 16. Viva questions and answers

**Q1. Why is this called peer-to-peer if there is a tracker?**
The tracker only stores *who has which file* (a few bytes per peer). All file data flows directly
between peers. Peers even work without the tracker using LAN discovery or direct connections.

**Q2. Why split files into pieces?**
(1) Download different pieces from different peers in parallel; (2) verify and re-download only a
small damaged piece instead of the whole file; (3) share pieces before the file is complete;
(4) resume at piece level.

**Q3. How is integrity guaranteed?**
Every piece has a SHA-1 hash in the metadata. Each received piece is hashed and compared before
writing. The metadata itself is identified by its SHA-1 (info hash), so a peer cannot send a fake
piece list. After the last piece, the whole-file SHA-1 is checked as well.

**Q4. What is the info hash?**
The SHA-1 of the metadata text (name, size, piece size, file hash, piece hashes). Identical files
with identical piece size have the same info hash everywhere, which lets peers find each other.

**Q5. How does multithreading work here?**
The upload server handles each incoming connection in a thread from a pool. A download starts one
worker thread per peer. All workers share a synchronized PieceManager so a piece is requested from
only one peer at a time.

**Q6. What is rarest-first?**
Among the pieces a peer can give us, we choose the one the fewest connected peers have. This
spreads rare pieces quickly and avoids a situation where one missing piece exists only on a peer
that leaves.

**Q7. How does resume work?**
Pieces are written at their exact byte offset into a pre-allocated `.part` file. On restart, every
piece is re-hashed; valid ones are kept and only the missing ones are downloaded.

**Q8. SHA-1 is considered weak. Why use it?**
BitTorrent v1 uses SHA-1, and the project brief specifies it. It reliably detects accidental
corruption. Against deliberate collision attacks SHA-256 (BitTorrent v2) is better; switching only
changes `HashUtil` and the hash length in `FileMeta`.

**Q9. What happens if a peer sends bad data on purpose?**
Each bad piece is rejected; after 5 bad pieces the peer is blocked for that download and the pieces
are fetched from other peers (demonstrated with `--corrupt`).

**Q10. How is path traversal prevented?**
File names from metadata are sanitized: only the last path component is used and characters such
as `\ / : * ? " < > |` are rejected, so a malicious name like `..\..\Windows\evil.dll` is refused.

**Q11. TCP or UDP?**
TCP for file transfer and tracker (reliable, ordered). UDP multicast only for LAN discovery, where a
lost packet just means "try again".

**Q12. Can it work over the internet?**
Yes if peers are reachable (public IP or port forwarding). NAT traversal (hole punching, relays) is
not implemented — see limitations.

---

## 17. Limitations and future work

- No NAT traversal: peers behind different home routers cannot connect directly without port forwarding.
- No encryption or user authentication (files on the LAN are public to all peers).
- One file per metadata (BitTorrent also supports folders).
- No DHT (tracker-less discovery across the internet); LAN discovery covers local networks only.
- SHA-1 could be upgraded to SHA-256.
- Possible extensions: TLS encryption, access control lists, DHT, choking/unchoking incentives (tit-for-tat),
  folder sharing, a desktop GUI.

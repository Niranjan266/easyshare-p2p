# EasyShare Messenger — desktop app

An IP Messenger-style Java desktop app (Swing). Run it on every PC in the same network: other PCs
appear automatically, and you can send messages, files and folders to them. Files travel in 256 KB
pieces, and every piece is checked with SHA-1.

This folder is self-contained: it has only the desktop app's source code and scripts.

## Requirements

- Windows 10/11
- JDK 17 or newer (`java -version`)
- PCs on the same Wi-Fi / LAN

## Files

| File | Purpose |
|---|---|
| `EasyShare Messenger.bat` | Start the app (builds it the first time) |
| `Second window (test on one PC).bat` | Open a second window named *Test PC 2* to try sending on one PC |
| `build.bat` | Compile `src` into `EasyShareMessenger.jar` |
| `src/easyshare/messenger/` | The app: window (`MessengerGui`), network service (`MessengerService`), settings |
| `src/easyshare/peer`, `meta`, `common`, `tracker` | P2P file transfer engine (pieces, SHA-1, resume) used to send files |

## How to use

1. Copy this folder to every PC and double-click **`EasyShare Messenger.bat`**.
2. When Windows Firewall asks about *Java(TM) Platform SE binary*, click **Allow**.
3. Other PCs appear in **Users on the network**. Click **Refresh** if needed, or **Add PC by IP...**.
4. Select a user (Ctrl+click for several), type a message, click **Add files...** / **Add folder...**
   or drag files onto the window, then click **Send** (or Ctrl+Enter).
5. The receiver gets a popup: **Save files**, **Decline** or **Reply**. Progress is shown in the
   **Transfers** tab. Received files are saved in `Downloads\EasyShare Received`
   (change it in **Settings...**).

## How it works

- **Discovery:** UDP broadcast on ports 2425–2429 (`ENTRY`, `ANSENTRY`, `EXIT`), like IP Messenger.
- **Messages:** TCP; the receiver confirms delivery.
- **Files:** the sender sends piece hashes with the message; after the receiver accepts, pieces are
  downloaded over TCP, each piece and the whole file are verified with SHA-1, and **Retry** resumes an
  interrupted transfer.

## Troubleshooting

| Problem | Fix |
|---|---|
| Nobody in the list | Same Wi-Fi? Allow Java in the firewall on both PCs, click **Refresh**. Guest/college Wi-Fi may block broadcasts — use **Add PC by IP...** or a phone hotspot. |
| "Could not reach ..." | The other PC closed the app or its firewall blocks Java. |
| Transfer stopped | The sender closed the app; reopen it and click **Retry**. |
